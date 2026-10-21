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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SHQueueCommands extends JavaPlugin {

    private final Map<UUID, List<String>> queuedCommands = new ConcurrentHashMap<>();
    private File queuedCommandsFile;
    private FileConfiguration queuedCommandsConfig;
    private String prefix; // Stocke le préfixe une fois chargé

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues(); // Charger le préfixe

        setupQueuedCommandsFile();
        loadQueuedCommandsFromFile();

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

    // Méthode pour recharger les valeurs de configuration mises en cache
    public void loadConfigValues() {
        reloadConfig(); // Assure que la config est la plus récente du disque
        this.prefix = ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.prefix", "&8[&6SHQC&8] &r"));
        // Tu pourrais charger d'autres valeurs ici si nécessaire
    }


    /**
     * Obtient un message formaté depuis la configuration.
     * Si le message configuré (sans le préfixe) est vide ou ne contient que des espaces,
     * une chaîne vide est retournée, ce qui n'enverra rien via sender.sendMessage("").
     *
     * @param key          La clé du message dans la section 'messages' de config.yml.
     * @param replacements Les paires de remplacement (ex: "%player%", playerName).
     * @return Le message formaté, ou une chaîne vide si le message est configuré comme vide.
     */
    public String getMessage(String key, String... replacements) {
        String messageContent = getConfig().getString("messages." + key);

        if (messageContent == null) {
            // Le message n'existe pas du tout dans la config, on utilise un message d'erreur par défaut
            // et on ajoute le préfixe car c'est un message d'erreur du plugin.
            messageContent = "&cMessage non trouvé dans config.yml: messages." + key;
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


    public long getExecutionDelayTicks() {
        return getConfig().getLong("execution-delay-ticks", 40L);
    }

    public void addCommandToQueue(UUID playerUUID, String command) {
        queuedCommands.computeIfAbsent(playerUUID, k -> new ArrayList<>()).add(command);
        // saveQueuedCommandsToFile(); // Décommenter pour une sauvegarde plus fréquente et immédiate
    }

    public List<String> getAndClearCommands(UUID playerUUID) {
        return queuedCommands.remove(playerUUID);
    }

    public Map<UUID, List<String>> getQueuedCommandsMap() {
        return queuedCommands;
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
        ConfigurationSection commandsSection = queuedCommandsConfig.getConfigurationSection("commands");
        if (commandsSection != null) {
            for (String uuidString : commandsSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidString);
                    List<String> commands = commandsSection.getStringList(uuidString); // Changé pour correspondre à la sauvegarde
                    if (!commands.isEmpty()) {
                        queuedCommands.put(uuid, new ArrayList<>(commands));
                    }
                } catch (IllegalArgumentException e) {
                    getLogger().warning("UUID invalide trouvé dans queued_commands.yml: " + uuidString);
                }
            }
        }
        getLogger().info(queuedCommands.values().stream().mapToInt(List::size).sum() + " commande(s) en attente chargées pour " + queuedCommands.size() + " joueur(s).");
    }

    public void saveQueuedCommandsToFile() {
        // Créer une nouvelle instance de configuration pour éviter de modifier l'ancienne
        FileConfiguration newConfig = new YamlConfiguration();
        ConfigurationSection commandsSection = newConfig.createSection("commands");

        for (Map.Entry<UUID, List<String>> entry : queuedCommands.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                commandsSection.set(entry.getKey().toString(), entry.getValue());
            }
        }

        try {
            newConfig.save(queuedCommandsFile); // Sauvegarder la nouvelle configuration
            // Mettre à jour la config en mémoire si nécessaire, ou simplement la recharger
            // queuedCommandsConfig = newConfig; // Optionnel, dépend si on la réutilise directement
            getLogger().info("Commandes en attente sauvegardées dans queued_commands.yml");
        } catch (IOException e) {
            getLogger().severe("Impossible de sauvegarder les commandes en attente: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    public OfflinePlayer getOfflinePlayerByName(String playerName) {
        Player onlinePlayer = Bukkit.getPlayerExact(playerName);
        if (onlinePlayer != null) {
            return onlinePlayer;
        }
        return Bukkit.getOfflinePlayer(playerName);
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
