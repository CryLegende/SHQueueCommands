package org.Cry_Legende.sHQueueCommands;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.List;
import java.util.UUID;

public class PlayerJoinListener implements Listener {

    private final SHQueueCommands plugin;

    public PlayerJoinListener(SHQueueCommands plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerUUID = event.getPlayer().getUniqueId();

        // Les commandes restent dans la file jusqu'à leur exécution : si le joueur repart pendant le délai
        // ou si le serveur s'arrête, elles sont conservées dans queued_commands.yml au lieu d'être perdues.
        List<String> pending = plugin.getQueuedCommandsMap().get(playerUUID);
        if (pending == null || pending.isEmpty()) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player == null || !player.isOnline()) {
                return; // Reparti : la file est gardée pour sa prochaine connexion
            }

            List<String> commandsToExecute = plugin.getAndClearCommands(playerUUID); // Récupère et supprime
            if (commandsToExecute == null || commandsToExecute.isEmpty()) {
                return; // Déjà traitées par une connexion précédente
            }

            int executedCount = 0;
            for (String cmd : commandsToExecute) {
                try {
                    plugin.executeCommandAsConsole(cmd, player);
                } catch (RuntimeException e) {
                    // Une commande en erreur ne doit pas faire perdre les suivantes
                    plugin.getLogger().warning("Commande en file en erreur pour " + player.getName() + " (" + cmd + ") : " + e.getMessage());
                }
                executedCount++;
            }
            plugin.sendMessage(player, plugin.getMessage("commands-executed-on-join", "%count%", String.valueOf(executedCount)));
            // Sauvegarder après l'exécution pour s'assurer que les commandes effacées le sont aussi sur disque
            plugin.saveQueuedCommandsToFile();
        }, plugin.getExecutionDelayTicks());
    }
}
