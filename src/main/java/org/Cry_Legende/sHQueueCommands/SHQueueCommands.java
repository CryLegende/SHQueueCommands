package org.Cry_Legende.sHQueueCommands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class SHQueueCommands extends JavaPlugin {

    // Intervalle entre deux passages de la purge des commandes trop anciennes (1 heure)
    private static final long PURGE_INTERVAL_TICKS = 20L * 60L * 60L;

    private final Map<UUID, List<QueuedCommand>> queuedCommands = new ConcurrentHashMap<>();
    private File queuedCommandsFile;
    private FileConfiguration queuedCommandsConfig;
    private FileConfiguration messages;
    private String prefix = ""; // Stocke le préfixe une fois chargé

    // Sauvegardes : chaque instantané est numéroté pour qu'une écriture asynchrone plus ancienne n'écrase jamais une plus récente
    private final Object saveLock = new Object();
    private final AtomicLong saveCounter = new AtomicLong();
    private long lastWrittenVersion = 0L;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues(); // Charger le préfixe

        setupQueuedCommandsFile();
        loadQueuedCommandsFromFile();

        // Purge au démarrage puis toutes les heures : un serveur qui ne redémarre presque jamais doit purger aussi
        purgeExpiredCommands();
        getServer().getScheduler().runTaskTimer(this, this::purgeExpiredCommands, PURGE_INTERVAL_TICKS, PURGE_INTERVAL_TICKS);

        QueueCommandExecutor commandExecutor = new QueueCommandExecutor(this);
        getCommand("queuecommand").setExecutor(commandExecutor);
        getCommand("queuecommand").setTabCompleter(commandExecutor);

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);

        getLogger().info("SHQueueCommands a été activé !");
    }

    @Override
    public void onDisable() {
        saveQueuedCommandsToFile();
        getLogger().info("SHQueueCommands a été désactivé !");
    }

    // Recharge config.yml et messages.yml depuis le disque
    public void loadConfigValues() {
        reloadConfig();
        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
        this.messages = YamlConfiguration.loadConfiguration(messagesFile);
        this.prefix = ChatColor.translateAlternateColorCodes('&', messages.getString("prefix", "&8[&6SHQC&8] &r"));
    }


    /**
     * Obtient un message formaté depuis messages.yml.
     * Si le message configuré (sans le préfixe) est vide ou ne contient que des espaces,
     * une chaîne vide est retournée, ce qui n'enverra rien via sender.sendMessage("").
     *
     * @param key          La clé du message dans messages.yml.
     * @param replacements Les paires de remplacement (ex: "%player%", playerName).
     * @return Le message formaté, ou une chaîne vide si le message est configuré comme vide.
     */
    public String getMessage(String key, String... replacements) {
        String messageContent = messages.getString(key);

        if (messageContent == null) {
            // Le message n'existe pas du tout dans messages.yml, on utilise un message d'erreur par défaut
            // et on ajoute le préfixe car c'est un message d'erreur du plugin.
            messageContent = "&cMessage non trouvé dans messages.yml: " + key;
            // Appliquer les remplacements même pour le message d'erreur par défaut
            for (int i = 0; i < replacements.length; i += 2) {
                if (i + 1 < replacements.length) {
                    messageContent = messageContent.replace(replacements[i], replacements[i + 1]);
                }
            }
            return this.prefix + ChatColor.translateAlternateColorCodes('&', messageContent);
        }

        // Vérifier si le message configuré est vide ou ne contient que des espaces
        if (messageContent.trim().isEmpty()) {
            return ""; // Retourne une chaîne vide, player.sendMessage("") ne fait rien.
        }

        // Appliquer les remplacements au contenu du message
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                messageContent = messageContent.replace(replacements[i], replacements[i + 1]);
            }
        }

        // Ajouter le préfixe et traduire les codes couleurs
        return this.prefix + ChatColor.translateAlternateColorCodes('&', messageContent);
    }


    /**
     * Envoie un message ; un message vide dans la config est désactivé (pas de ligne blanche dans le chat).
     */
    public void sendMessage(CommandSender target, String message) {
        if (message != null && !message.isEmpty()) {
            target.sendMessage(message);
        }
    }

    public long getExecutionDelayTicks() {
        return getConfig().getLong("execution-delay-ticks", 40L);
    }

    // Durée de conservation d'une commande en file, en jours ; 0 ou moins désactive la purge
    public long getPurgeAfterDays() {
        return getConfig().getLong("purge-after-days", 90L);
    }

    public void addCommandToQueue(UUID playerUUID, String command) {
        queuedCommands.computeIfAbsent(playerUUID, k -> new ArrayList<>()).add(new QueuedCommand(command, System.currentTimeMillis()));
        // saveQueuedCommandsToFile(); // Décommenter pour une sauvegarde plus fréquente et immédiate
    }

    public List<String> getAndClearCommands(UUID playerUUID) {
        List<QueuedCommand> removed = queuedCommands.remove(playerUUID);
        if (removed == null) {
            return null;
        }
        return removed.stream().map(QueuedCommand::command).collect(Collectors.toList());
    }

    public Map<UUID, List<QueuedCommand>> getQueuedCommandsMap() {
        return queuedCommands;
    }

    /**
     * Supprime de la file les commandes en attente depuis plus de purge-after-days jours.
     * Les joueurs connectés sont ignorés : leurs commandes sont en cours d'exécution.
     *
     * @return Le nombre de commandes supprimées.
     */
    public int purgeExpiredCommands() {
        long days = getPurgeAfterDays();
        if (days <= 0) {
            return 0;
        }
        long limit = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
        int purgedTotal = 0;

        Iterator<Map.Entry<UUID, List<QueuedCommand>>> players = queuedCommands.entrySet().iterator();
        while (players.hasNext()) {
            Map.Entry<UUID, List<QueuedCommand>> entry = players.next();
            if (Bukkit.getPlayer(entry.getKey()) != null) {
                continue;
            }
            List<QueuedCommand> commands = entry.getValue();
            int before = commands.size();
            commands.removeIf(queued -> queued.queuedAt() < limit);
            int purged = before - commands.size();
            if (purged > 0) {
                purgedTotal += purged;
                String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                getLogger().info(purged + " commande(s) en file depuis plus de " + days + " jours purgée(s) pour "
                    + (name != null ? name : entry.getKey().toString()));
            }
            if (commands.isEmpty()) {
                players.remove();
            }
        }

        if (purgedTotal > 0) {
            saveQueuedCommandsToFile();
        }
        return purgedTotal;
    }

    private void setupQueuedCommandsFile() {
        queuedCommandsFile = new File(getDataFolder(), "queued_commands.yml");
        if (!queuedCommandsFile.exists()) {
            try {
                if (queuedCommandsFile.getParentFile() != null) {
                    queuedCommandsFile.getParentFile().mkdirs();
                }
                queuedCommandsFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Impossible de créer le fichier queued_commands.yml: " + e.getMessage());
            }
        }
        queuedCommandsConfig = YamlConfiguration.loadConfiguration(queuedCommandsFile);
    }

    private void loadQueuedCommandsFromFile() {
        queuedCommands.clear();
        long now = System.currentTimeMillis();
        int undated = 0;
        ConfigurationSection commandsSection = queuedCommandsConfig.getConfigurationSection("commands");
        if (commandsSection != null) {
            for (String uuidString : commandsSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidString);
                    List<QueuedCommand> commands = new ArrayList<>();
                    List<?> raw = commandsSection.getList(uuidString);
                    if (raw != null) {
                        for (Object element : raw) {
                            if (element instanceof Map<?, ?> map) {
                                Object command = map.get("command");
                                if (command == null) {
                                    continue;
                                }
                                Object queuedAt = map.get("queued-at");
                                if (queuedAt instanceof Number number) {
                                    commands.add(new QueuedCommand(command.toString(), number.longValue()));
                                } else {
                                    commands.add(new QueuedCommand(command.toString(), now));
                                    undated++;
                                }
                            } else if (element != null) {
                                // Ancien format : simple chaîne sans date, le délai de purge démarre au chargement
                                commands.add(new QueuedCommand(element.toString(), now));
                                undated++;
                            }
                        }
                    }
                    if (!commands.isEmpty()) {
                        queuedCommands.put(uuid, commands);
                    }
                } catch (IllegalArgumentException e) {
                    getLogger().warning("UUID invalide trouvé dans queued_commands.yml: " + uuidString);
                }
            }
        }
        getLogger().info(queuedCommands.values().stream().mapToInt(List::size).sum() + " commande(s) en attente chargées pour " + queuedCommands.size() + " joueur(s).");
        if (undated > 0) {
            // Écrit tout de suite les dates : sans cela, un arrêt brutal les ferait repartir de zéro au prochain chargement
            getLogger().info(undated + " commande(s) sans date : elles sont datées d'aujourd'hui pour la purge.");
            saveQueuedCommandsToFile();
        }
    }

    public void saveQueuedCommandsToFile() {
        // Instantané construit sur le thread appelant (cohérent avec la file en mémoire)
        FileConfiguration newConfig = new YamlConfiguration();
        ConfigurationSection commandsSection = newConfig.createSection("commands");

        for (Map.Entry<UUID, List<QueuedCommand>> entry : queuedCommands.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                List<Map<String, Object>> serialized = new ArrayList<>();
                for (QueuedCommand queued : new ArrayList<>(entry.getValue())) {
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("command", queued.command());
                    fields.put("queued-at", queued.queuedAt());
                    serialized.add(fields);
                }
                commandsSection.set(entry.getKey().toString(), serialized);
            }
        }

        final String data = newConfig.saveToString();
        final long version = saveCounter.incrementAndGet();

        // Écriture hors du thread principal ; à l'arrêt du plugin le scheduler n'accepte plus de tâche, on écrit directement
        if (isEnabled()) {
            getServer().getScheduler().runTaskAsynchronously(this, () -> writeQueuedCommands(data, version));
        } else {
            writeQueuedCommands(data, version);
        }
    }

    private void writeQueuedCommands(String data, long version) {
        synchronized (saveLock) {
            if (version < lastWrittenVersion) {
                return; // Un instantané plus récent est déjà sur le disque
            }
            try {
                // Écriture dans un fichier temporaire puis remplacement : un crash en cours d'écriture ne tronque pas la file
                File tmp = new File(queuedCommandsFile.getParentFile(), queuedCommandsFile.getName() + ".tmp");
                Files.writeString(tmp.toPath(), data, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp.toPath(), queuedCommandsFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp.toPath(), queuedCommandsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                lastWrittenVersion = version;
                getLogger().info("Commandes en attente sauvegardées dans queued_commands.yml");
            } catch (IOException e) {
                getLogger().severe("Impossible de sauvegarder les commandes en attente: " + e.getMessage());
            }
        }
    }

    /**
     * Résout un joueur par son nom sans requête réseau. Le réseau identifie les joueurs par leur UUID hors ligne
     * (md5 de "OfflinePlayer:<nom>") : Bukkit.getOfflinePlayer(String) interroge Mojang sur le thread principal
     * et renvoie l'UUID premium des comptes originaux.
     */
    public OfflinePlayer getOfflinePlayerByName(String playerName) {
        Player onlinePlayer = Bukkit.getPlayerExact(playerName);
        if (onlinePlayer != null) {
            return onlinePlayer;
        }
        // Le cache de noms rétablit la casse exacte, qui change l'UUID hors ligne
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(playerName);
        String exactName = cached != null && cached.getName() != null ? cached.getName() : playerName;
        return Bukkit.getOfflinePlayer(UUID.nameUUIDFromBytes(("OfflinePlayer:" + exactName).getBytes(StandardCharsets.UTF_8)));
    }

    public void executeCommandAsConsole(String command, Player playerContext) {
        String processedCommand = command;
        if (playerContext != null) {
            processedCommand = processedCommand.replace("%player%", playerContext.getName())
                .replace("%uuid%", playerContext.getUniqueId().toString());
        } else {
            processedCommand = processedCommand.replace("%player%", "UNKNOWN_PLAYER")
                .replace("%uuid%", "UNKNOWN_UUID");
        }

        if (processedCommand.startsWith("/")) {
            processedCommand = processedCommand.substring(1);
        }

        getLogger().info("Exécution de la commande (console): " + processedCommand);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCommand);
    }
}
