package org.Cry_Legende.sHQueueCommands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class QueueCommandExecutor implements CommandExecutor, TabCompleter {

    private static final String EXECUTE = "shqueuecommands.execute";
    private static final String RELOAD = "shqueuecommands.admin.reload";
    private static final String LIST = "shqueuecommands.admin.list";
    private static final String CLEAR = "shqueuecommands.admin.clear";

    // Lignes affichées au plus par /shqueuecommands list
    private static final int LIST_LINES = 10;

    private final SHQueueCommands plugin;

    public QueueCommandExecutor(SHQueueCommands plugin) {
        this.plugin = plugin;
    }

    /** Les sous-commandes que l'expéditeur a le droit d'utiliser, dans l'ordre d'affichage. */
    private static List<String> allowed(CommandSender sender) {
        List<String> allowed = new ArrayList<>();
        if (sender.hasPermission(EXECUTE)) allowed.add("queue");
        if (sender.hasPermission(RELOAD)) allowed.add("reload");
        if (sender.hasPermission(LIST)) allowed.add("list");
        if (sender.hasPermission(CLEAR)) allowed.add("clear");
        return allowed;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> allowed = allowed(sender);
        if (allowed.isEmpty()) {
            plugin.sendMessage(sender, plugin.getMessage("no-permission"));
            return true;
        }

        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!allowed.contains(sub)) {
            // Sous-commande inconnue, ou connue mais sans le droit : le même usage, limité à ce que l'expéditeur peut faire
            plugin.sendMessage(sender, plugin.getMessage("usage", "%subcommands%", String.join("|", allowed)));
            return true;
        }

        switch (sub) {
            case "reload" -> plugin.reloadFiles(sender);
            case "list" -> list(sender, args);
            case "clear" -> clear(sender, args);
            default -> queue(sender, args);
        }
        return true;
    }

    private void queue(CommandSender sender, String[] args) {
        if (args.length < 3) { // queue <Joueur> <Commande ...>
            plugin.sendMessage(sender, plugin.getMessage("invalid-usage"));
            return;
        }

        String playerName = args[1];
        String commandToExecute = String.join(" ", Arrays.copyOfRange(args, 2, args.length));

        Player targetOnlinePlayer = Bukkit.getPlayerExact(playerName);

        if (targetOnlinePlayer != null && targetOnlinePlayer.isOnline()) {
            // Le joueur est en ligne, exécuter la commande directement
            plugin.executeCommandAsConsole(commandToExecute, targetOnlinePlayer);
            plugin.sendMessage(sender, plugin.getMessage("command-executed-immediately", "%player%", targetOnlinePlayer.getName(), "%command%", commandToExecute));
        } else if (plugin.network() != null) {
            // Mode réseau : envoyée sur le serveur du joueur, ou mise dans la file commune
            plugin.network().queue(sender, playerName, commandToExecute);
        } else {
            // Le joueur est hors ligne ou n'existe pas, chercher son UUID
            OfflinePlayer targetOfflinePlayer = plugin.getOfflinePlayerByName(playerName);
            if (targetOfflinePlayer != null && (targetOfflinePlayer.hasPlayedBefore() || targetOfflinePlayer.isOnline())) {
                UUID playerUUID = targetOfflinePlayer.getUniqueId();
                plugin.addCommandToQueue(playerUUID, commandToExecute);
                plugin.sendMessage(sender, plugin.getMessage("command-queued", "%player%", targetOfflinePlayer.getName() != null ? targetOfflinePlayer.getName() : playerName, "%command%", commandToExecute));
                plugin.saveQueuedCommandsToFile();
            } else {
                plugin.sendMessage(sender, plugin.getMessage("player-not-found", "%player%", playerName));
            }
        }
    }

    /** Sans argument : le total et les files les plus longues ; avec un joueur : ses commandes en attente. */
    private void list(CommandSender sender, String[] args) {
        if (plugin.network() != null) {
            plugin.sendMessage(sender, plugin.getMessage("network-only-local"));
            return;
        }
        long now = System.currentTimeMillis();
        Map<UUID, List<QueuedCommand>> queue = plugin.getQueuedCommandsMap();

        if (args.length >= 2) {
            UUID uuid = plugin.getOfflinePlayerByName(args[1]).getUniqueId();
            String name = displayName(uuid, args[1]);
            List<QueuedCommand> commands = queue.get(uuid);
            if (commands == null || commands.isEmpty()) {
                plugin.sendMessage(sender, plugin.getMessage("list-player-none", "%player%", name));
                return;
            }
            plugin.sendMessage(sender, plugin.getMessage("list-player-header", "%player%", name, "%count%", String.valueOf(commands.size())));
            int shown = Math.min(LIST_LINES, commands.size());
            for (QueuedCommand queued : commands.subList(0, shown)) {
                plugin.sendMessage(sender, plugin.getMessage("list-command", "%command%", queued.command(), "%age%", age(now - queued.queuedAt())));
            }
            if (commands.size() > shown) {
                plugin.sendMessage(sender, plugin.getMessage("list-more", "%more%", String.valueOf(commands.size() - shown)));
            }
            return;
        }

        int total = 0;
        int players = 0;
        List<Map.Entry<UUID, List<QueuedCommand>>> entries = new ArrayList<>();
        for (Map.Entry<UUID, List<QueuedCommand>> entry : queue.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
            total += entry.getValue().size();
            players++;
            entries.add(entry);
        }
        if (total == 0) {
            plugin.sendMessage(sender, plugin.getMessage("list-empty"));
            return;
        }
        plugin.sendMessage(sender, plugin.getMessage("list-summary", "%count%", String.valueOf(total), "%players%", String.valueOf(players)));
        entries.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        for (Map.Entry<UUID, List<QueuedCommand>> entry : entries.subList(0, Math.min(LIST_LINES, entries.size()))) {
            long oldest = entry.getValue().stream().mapToLong(QueuedCommand::queuedAt).min().orElse(now);
            plugin.sendMessage(sender, plugin.getMessage("list-player", "%player%", displayName(entry.getKey(), entry.getKey().toString()),
                "%count%", String.valueOf(entry.getValue().size()), "%age%", age(now - oldest)));
        }
        if (entries.size() > LIST_LINES) {
            plugin.sendMessage(sender, plugin.getMessage("list-more", "%more%", String.valueOf(entries.size() - LIST_LINES)));
        }
    }

    /** Retire toutes les commandes en attente d'un joueur. */
    private void clear(CommandSender sender, String[] args) {
        if (plugin.network() != null) {
            plugin.sendMessage(sender, plugin.getMessage("network-only-local"));
            return;
        }
        if (args.length != 2) {
            plugin.sendMessage(sender, plugin.getMessage("clear-usage"));
            return;
        }
        UUID uuid = plugin.getOfflinePlayerByName(args[1]).getUniqueId();
        String name = displayName(uuid, args[1]);
        List<QueuedCommand> removed = plugin.getQueuedCommandsMap().remove(uuid);
        if (removed == null || removed.isEmpty()) {
            plugin.sendMessage(sender, plugin.getMessage("list-player-none", "%player%", name));
            return;
        }
        plugin.getLogger().info(sender.getName() + " a retiré " + removed.size() + " commande(s) en file de " + name);
        plugin.saveQueuedCommandsToFile();
        plugin.sendMessage(sender, plugin.getMessage("clear-done", "%player%", name, "%count%", String.valueOf(removed.size())));
    }

    /** Pseudo du joueur d'après son UUID (sans requête réseau), ou le texte de repli. */
    private static String displayName(UUID uuid, String fallback) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : fallback;
    }

    /** Durée en jours et heures, heures et minutes, ou minutes. */
    private static String age(long millis) {
        long minutes = Math.max(0, TimeUnit.MILLISECONDS.toMinutes(millis));
        if (minutes >= 24 * 60) return minutes / (24 * 60) + " j " + (minutes / 60) % 24 + " h";
        if (minutes >= 60) return minutes / 60 + " h " + minutes % 60 + " min";
        return minutes + " min";
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        List<String> allowed = allowed(sender);
        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            allowed.stream().filter(name -> name.startsWith(input)).forEach(completions::add);
        } else if (args.length == 2 && allowed.contains(args[0].toLowerCase(Locale.ROOT))
            && (args[0].equalsIgnoreCase("queue") || args[0].equalsIgnoreCase("list") || args[0].equalsIgnoreCase("clear"))) {
            String input = args[1].toLowerCase(Locale.ROOT);
            Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(input))
                .forEach(completions::add);
            // Pourrait aussi ajouter les joueurs hors ligne, mais la liste peut être très longue
        }
        // Pas de complétion pour la commande elle-même (args.length >= 3) car ça peut être n'importe quoi
        return completions.stream().sorted().collect(Collectors.toList());
    }
}
