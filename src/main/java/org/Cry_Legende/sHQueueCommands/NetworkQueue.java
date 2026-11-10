package org.Cry_Legende.sHQueueCommands;

import com.google.gson.JsonObject;
import org.Cry_Legende.sHQueueCommands.network.NetBus;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * File en mode réseau (SHNetwork présent) : une seule file pour tous les serveurs, en MariaDB.
 *
 * <ul>
 *   <li>Joueur connecté sur ce serveur : la commande s'exécute tout de suite, comme avant.</li>
 *   <li>Joueur connecté sur un autre serveur : la commande y est envoyée ({@code runFor} de SHNetwork), avec son pseudo
 *   et son UUID déjà remplacés.</li>
 *   <li>Joueur hors ligne : la commande part dans la file commune. À sa connexion, sur n'importe quel serveur, ce
 *   serveur réclame ses commandes (une seule fois pour tout le réseau), les exécute puis les retire.</li>
 *   <li>La purge des vieilles commandes et le contrôle des réclamations orphelines ne tournent que sur le meneur.</li>
 * </ul>
 */
final class NetworkQueue {

    private static final String LEADER_TASK = "shqueuecommands";
    private static final long PURGE_INTERVAL_TICKS = 20L * 60L * 60L;
    private static final long STALE_MILLIS = TimeUnit.MINUTES.toMillis(10);

    private final SHQueueCommands plugin;
    private final NetBus bus;
    private final QueueStore store;
    private final String channel;
    private final String firstSeenTable;
    private final Set<Long> staleWarned = ConcurrentHashMap.newKeySet();
    private NetBus.Leader leader;
    private BukkitTask purgeTask;

    NetworkQueue(SHQueueCommands plugin, NetBus bus, QueueStore store, String channel, String firstSeenTable) {
        this.plugin = plugin;
        this.bus = bus;
        this.store = store;
        this.channel = channel;
        this.firstSeenTable = firstSeenTable;
    }

    void enable() {
        bus.listen(channel, this::onMessage);
        leader = bus.leader(LEADER_TASK, () -> {
            plugin.getLogger().info("Ce serveur purge la file commune du réseau.");
            maintenance();
        }, () -> plugin.getLogger().info("Ce serveur ne purge plus la file commune."));
        purgeTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (isLeader()) {
                maintenance();
            }
        }, PURGE_INTERVAL_TICKS, PURGE_INTERVAL_TICKS);
    }

    void disable() {
        if (purgeTask != null) {
            purgeTask.cancel();
            purgeTask = null;
        }
        if (leader != null) {
            leader.release();
            leader = null;
        }
        store.close();
    }

    boolean isLeader() {
        NetBus.Leader current = leader;
        return current != null && current.isLeader();
    }

    private void async(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    private void sync(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ mise en file

    /**
     * /qc queue pour un joueur qui n'est pas connecté sur ce serveur (thread principal) : envoyé sur son serveur s'il
     * est connecté ailleurs, sinon mis dans la file commune s'il est déjà venu sur le réseau.
     */
    void queue(CommandSender sender, String playerName, String command) {
        NetBus.Online online = bus.find(playerName);
        if (online != null && !online.server().equals(bus.serverName())) {
            bus.runFor(online.uuid(), substitute(command, online.name(), online.uuid()));
            plugin.sendMessage(sender, plugin.getMessage("command-sent-to-server", "%player%", online.name(), "%server%", online.server(),
                "%command%", command));
            return;
        }
        // Pseudo exact (il fixe l'UUID hors ligne) : présence, cache de ce serveur, puis table des premières connexions
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(playerName);
        String localName = cached != null && cached.getName() != null ? cached.getName() : null;
        boolean playedHere = localName != null && Bukkit.getOfflinePlayer(offlineUuid(localName)).hasPlayedBefore();
        String origin = bus.serverName();
        async(() -> {
            String exact = online != null ? online.name() : playedHere ? localName : store.firstSeen(firstSeenTable, playerName);
            if (exact == null) {
                sync(() -> plugin.sendMessage(sender, plugin.getMessage("player-not-found", "%player%", playerName)));
                return;
            }
            UUID uuid = online != null ? online.uuid() : offlineUuid(exact);
            try {
                store.insert(uuid, exact, command, System.currentTimeMillis(), origin);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Commande perdue pour " + exact + " (file commune injoignable) : " + command, e);
                sync(() -> plugin.sendMessage(sender, plugin.getMessage("queue-error", "%player%", exact, "%command%", command)));
                return;
            }
            sync(() -> {
                plugin.sendMessage(sender, plugin.getMessage("command-queued", "%player%", exact, "%command%", command));
                // Le joueur a pu se connecter pendant l'écriture : son serveur relit la file
                JsonObject o = new JsonObject();
                o.addProperty("type", "queued");
                o.addProperty("uuid", uuid.toString());
                bus.publish(channel, o);
            });
        });
    }

    private static String substitute(String command, String name, UUID uuid) {
        String processed = command.replace("%player%", name).replace("%uuid%", uuid.toString());
        return processed.startsWith("/") ? processed.substring(1) : processed;
    }

    // ------------------------------------------------------------------ exécution

    /** Connexion d'un joueur sur ce serveur : ses commandes sont réclamées après le délai, s'il est encore là. */
    void joined(Player player) {
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> claimAndRun(uuid), plugin.getExecutionDelayTicks());
    }

    private void claimAndRun(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return; // reparti : sa file l'attend sur le serveur où il arrivera
        }
        String token = UUID.randomUUID().toString();
        String server = bus.serverName();
        async(() -> {
            List<QueueStore.Claimed> claimed;
            try {
                claimed = store.claim(uuid, server, token);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "File de " + uuid + " illisible, réessai à sa prochaine connexion", e);
                return;
            }
            if (claimed.isEmpty()) {
                return;
            }
            sync(() -> run(uuid, token, claimed));
        });
    }

    /** Thread principal : exécution des commandes réclamées, puis retrait de la file. */
    private void run(UUID uuid, String token, List<QueueStore.Claimed> claimed) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            async(() -> {
                try {
                    store.release(token);
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Commandes de " + uuid + " restées réclamées (jeton " + token + ") : à libérer à la main", e);
                }
            });
            return;
        }
        int executed = 0;
        for (QueueStore.Claimed queued : claimed) {
            try {
                plugin.executeCommandAsConsole(queued.command(), player);
            } catch (RuntimeException e) {
                // Une commande en erreur ne doit pas faire perdre les suivantes
                plugin.getLogger().warning("Commande en file en erreur pour " + player.getName() + " (" + queued.command() + ") : " + e.getMessage());
            }
            executed++;
        }
        plugin.sendMessage(player, plugin.getMessage("commands-executed-on-join", "%count%", String.valueOf(executed)));
        async(() -> {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    store.delete(token);
                    return;
                } catch (SQLException e) {
                    if (attempt == 2) {
                        plugin.getLogger().log(Level.SEVERE, "Commandes exécutées pour " + uuid + " mais pas retirées de la file (jeton " + token
                            + ") : à supprimer à la main, elles ne seront pas relancées", e);
                    }
                }
            }
        });
    }

    private void onMessage(JsonObject o) {
        if (!"queued".equals(o.has("type") ? o.get("type").getAsString() : "")) {
            return;
        }
        try {
            UUID uuid = UUID.fromString(o.get("uuid").getAsString());
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> claimAndRun(uuid), plugin.getExecutionDelayTicks());
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Message réseau de la file illisible : " + o, e);
        }
    }

    // ------------------------------------------------------------------ entretien (meneur)

    /** Purge des vieilles commandes, et signalement des réclamations orphelines. */
    void maintenance() {
        long days = plugin.getPurgeAfterDays();
        async(() -> {
            if (days > 0) {
                try {
                    List<QueueStore.Purged> purged = store.purge(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days));
                    for (QueueStore.Purged p : purged) {
                        plugin.getLogger().info(p.count() + " commande(s) en file depuis plus de " + days + " jours purgée(s) pour "
                            + (p.name() != null ? p.name() : p.uuid()));
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Purge de la file commune impossible", e);
                }
            }
            try {
                for (QueueStore.Stale stale : store.stale(System.currentTimeMillis() - STALE_MILLIS)) {
                    if (staleWarned.add(stale.id())) {
                        plugin.getLogger().severe("Commande réclamée par " + stale.server() + " sans suite pour " + (stale.name() != null ? stale.name() : stale.uuid())
                            + " (ligne " + stale.id() + " de " + QueueStore.TABLE + ") : " + stale.command()
                            + ". Serveur arrêté pendant l'exécution : vérifier si elle a été donnée, puis supprimer la ligne ou vider son claim_token.");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Contrôle des réclamations de la file impossible", e);
            }
        });
    }
}
