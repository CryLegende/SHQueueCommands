package org.Cry_Legende.sHQueueCommands;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.UUID;

public class PlayerJoinListener implements Listener {

    private final SHQueueCommands plugin;

    public PlayerJoinListener(SHQueueCommands plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        List<String> commandsToExecute = plugin.getAndClearCommands(playerUUID); // Récupère et supprime

        if (commandsToExecute != null && !commandsToExecute.isEmpty()) {
            long delay = plugin.getExecutionDelayTicks();
            final int commandCount = commandsToExecute.size(); // Pour le message

            new BukkitRunnable() {
                @Override
                public void run() {
                    int executedCount = 0;
                    for (String cmd : commandsToExecute) {
                        plugin.executeCommandAsConsole(cmd, player);
                        executedCount++;
                    }
                    if (executedCount > 0) {
                        player.sendMessage(plugin.getMessage("commands-executed-on-join", "%count%", String.valueOf(executedCount)));
                        // Sauvegarder après l'exécution pour s'assurer que les commandes effacées le sont aussi sur disque
                        plugin.saveQueuedCommandsToFile();
                    }
                }
            }.runTaskLater(plugin, delay);
        }
    }
}