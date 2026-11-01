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

            // Exécute et sauvegarde la file ; une commande azlink money peut attendre la réponse du site
            plugin.runCommands(player, commandsToExecute, true);
            plugin.sendMessage(player, plugin.getMessage("commands-executed-on-join", "%count%", String.valueOf(commandsToExecute.size())));
        }, plugin.getExecutionDelayTicks());
    }
}
