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
import java.util.UUID;
import java.util.stream.Collectors;

public class QueueCommandExecutor implements CommandExecutor, TabCompleter {

    private final SHQueueCommands plugin;

    public QueueCommandExecutor(SHQueueCommands plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("shqueuecommands.execute")) {
            sender.sendMessage(plugin.getMessage("no-permission"));
            return true;
        }

        if (args.length < 1 || !args[0].equalsIgnoreCase("queue")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (sender.hasPermission("shqueuecommands.reload")) { // Permission distincte pour reload
                    plugin.reloadConfig(); // recharge config.yml
                    // Il faudrait aussi recharger les messages ici si on les stocke séparément
                    plugin.saveDefaultConfig(); // S'assure que les nouvelles options sont là
                    // Recharger les valeurs de la config qui sont stockées dans des variables
                    try {
                        // On doit recharger les valeurs mises en cache
                        // Pour l'instant, on se contente de recharger le config.yml standard
                        // Si vous avez des valeurs mises en cache dans le plugin.java, il faut les mettre à jour
                        // plugin.loadConfigValues(); // Décommentez si vous avez cette méthode
                        sender.sendMessage(plugin.getMessage("prefix") + "Configuration rechargée.");
                    } catch (Exception e) {
                        sender.sendMessage(plugin.getMessage("prefix") + "§cErreur lors du rechargement de la configuration.");
                        plugin.getLogger().severe("Erreur lors du rechargement de la configuration: " + e.getMessage());
                    }
                    return true;
                } else {
                    sender.sendMessage(plugin.getMessage("no-permission"));
                    return true;
                }
            }
            sender.sendMessage(plugin.getMessage("invalid-usage"));
            return true;
        }

        if (args.length < 3) { // queue <Joueur> <Commande ...>
            sender.sendMessage(plugin.getMessage("invalid-usage"));
            return true;
        }

        String playerName = args[1];
        String commandToExecute = String.join(" ", Arrays.copyOfRange(args, 2, args.length));

        Player targetOnlinePlayer = Bukkit.getPlayerExact(playerName);

        if (targetOnlinePlayer != null && targetOnlinePlayer.isOnline()) {
            // Le joueur est en ligne, exécuter la commande directement
            plugin.executeCommandAsConsole(commandToExecute, targetOnlinePlayer);
            sender.sendMessage(plugin.getMessage("command-executed-immediately", "%player%", targetOnlinePlayer.getName(), "%command%", commandToExecute));
        } else {
            // Le joueur est hors ligne ou n'existe pas, chercher son UUID
            OfflinePlayer targetOfflinePlayer = plugin.getOfflinePlayerByName(playerName);
            if (targetOfflinePlayer != null && (targetOfflinePlayer.hasPlayedBefore() || targetOfflinePlayer.isOnline())) {
                UUID playerUUID = targetOfflinePlayer.getUniqueId();
                plugin.addCommandToQueue(playerUUID, commandToExecute);
                sender.sendMessage(plugin.getMessage("command-queued", "%player%", targetOfflinePlayer.getName(), "%command%", commandToExecute));
                // Sauvegarder immédiatement après l'ajout peut être une option, mais attention aux performances
                plugin.saveQueuedCommandsToFile();
            } else {
                sender.sendMessage(plugin.getMessage("player-not-found", "%player%", playerName));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            if ("queue".startsWith(args[0].toLowerCase())) completions.add("queue");
            if ("reload".startsWith(args[0].toLowerCase()) && sender.hasPermission("shqueuecommands.reload"))
                completions.add("reload");

        } else if (args.length == 2 && args[0].equalsIgnoreCase("queue")) {
            String input = args[1].toLowerCase();
            Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(input))
                .forEach(completions::add);
            // Pourrait aussi ajouter les joueurs hors ligne, mais la liste peut être très longue
        }
        // Pas de complétion pour la commande elle-même (args.length >= 3) car ça peut être n'importe quoi
        return completions.stream().sorted().collect(Collectors.toList());
    }
}
