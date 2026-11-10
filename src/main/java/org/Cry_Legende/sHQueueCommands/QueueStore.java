package org.Cry_Legende.sHQueueCommands;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * File réseau en MariaDB (table {@code shqc_queue}), commune à tous les serveurs. Chargée seulement en mode réseau :
 * HikariCP et le pilote MariaDB viennent de {@code libraries:} dans plugin.yml.
 *
 * <p>Les commandes d'un joueur sont réclamées par un seul serveur : un UPDATE conditionnel pose un jeton sur les lignes
 * libres, puis elles sont relues par ce jeton. Une fois exécutées, elles sont supprimées.</p>
 *
 * <p>Toutes les méthodes bloquent : jamais sur le fil principal.</p>
 */
final class QueueStore {

    static final String TABLE = "shqc_queue";
    private static final String STATE = "shqc_state";
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)?");

    /** Commande réclamée, prête à être exécutée. */
    record Claimed(long id, String command) {
    }

    /** Commandes purgées d'un joueur. */
    record Purged(String uuid, String name, int count) {
    }

    /** Réclamation restée en place (serveur arrêté entre la réclamation et la suppression). */
    record Stale(long id, String uuid, String name, String command, String server, long claimedAt) {
    }

    private final HikariDataSource pool;

    QueueStore(String url, String user, String password, int timeoutMs) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHQueueCommands");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(timeoutMs));
        hikari.setMaximumPoolSize(3);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        hikari.setKeepaliveTime(60_000);
        this.pool = new HikariDataSource(hikari);
    }

    Connection connection() throws SQLException {
        return pool.getConnection();
    }

    void close() {
        pool.close();
    }

    void createTables() throws SQLException {
        try (Connection c = connection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "uuid CHAR(36) NOT NULL,"
                + "name VARCHAR(16) NULL,"
                + "command TEXT NOT NULL,"
                + "queued_at BIGINT NOT NULL,"
                + "origin VARCHAR(32) NOT NULL,"
                + "claim_token CHAR(36) NULL,"
                + "claimed_by VARCHAR(32) NULL,"
                + "claimed_at BIGINT NULL,"
                + "PRIMARY KEY (id),"
                + "KEY idx_uuid (uuid, claim_token),"
                + "KEY idx_token (claim_token),"
                + "KEY idx_queued (queued_at)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + STATE + " ("
                + "k VARCHAR(64) NOT NULL,"
                + "v VARCHAR(255) NULL,"
                + "PRIMARY KEY (k)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        }
    }

    /**
     * Reprend une seule fois, pour tout le réseau, la file queued_commands.yml de ce serveur (dates conservées). Le
     * premier serveur qui démarre en mode réseau pose le drapeau {@code import_yaml} dans la même transaction que la copie.
     *
     * @return nombre de commandes reprises, ou -1 si la reprise avait déjà été faite
     */
    int importOnce(Map<UUID, List<QueuedCommand>> queue, String server) throws SQLException {
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + STATE + " (k, v) VALUES ('import_yaml', ?)")) {
                    ps.setString(1, server + " " + System.currentTimeMillis());
                    if (ps.executeUpdate() == 0) {
                        c.rollback();
                        return -1;
                    }
                }
                int copied = 0;
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + TABLE + " (uuid, name, command, queued_at, origin) VALUES (?, NULL, ?, ?, ?)")) {
                    for (Map.Entry<UUID, List<QueuedCommand>> entry : queue.entrySet()) {
                        for (QueuedCommand queued : entry.getValue()) {
                            ps.setString(1, entry.getKey().toString());
                            ps.setString(2, queued.command());
                            ps.setLong(3, queued.queuedAt());
                            ps.setString(4, server);
                            ps.addBatch();
                            copied++;
                        }
                    }
                    ps.executeBatch();
                }
                c.commit();
                return copied;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    void insert(UUID uuid, String name, String command, long queuedAt, String origin) throws SQLException {
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO " + TABLE + " (uuid, name, command, queued_at, origin) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, command);
            ps.setLong(4, queuedAt);
            ps.setString(5, origin);
            ps.executeUpdate();
        }
    }

    /** Réclame les commandes libres du joueur pour ce serveur et les rend dans l'ordre de mise en file. */
    List<Claimed> claim(UUID uuid, String server, String token) throws SQLException {
        List<Claimed> out = new ArrayList<>();
        try (Connection c = connection()) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + TABLE + " SET claim_token = ?, claimed_by = ?, claimed_at = ? "
                + "WHERE uuid = ? AND claim_token IS NULL")) {
                ps.setString(1, token);
                ps.setString(2, server);
                ps.setLong(3, System.currentTimeMillis());
                ps.setString(4, uuid.toString());
                if (ps.executeUpdate() == 0) {
                    return out;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT id, command FROM " + TABLE + " WHERE claim_token = ? ORDER BY id")) {
                ps.setString(1, token);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Claimed(rs.getLong(1), rs.getString(2)));
                    }
                }
            }
        }
        return out;
    }

    /** Commandes exécutées : retirées de la file. */
    int delete(String token) throws SQLException {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("DELETE FROM " + TABLE + " WHERE claim_token = ?")) {
            ps.setString(1, token);
            return ps.executeUpdate();
        }
    }

    /** Le joueur est reparti avant l'exécution : ses commandes redeviennent libres pour sa prochaine connexion. */
    int release(String token) throws SQLException {
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement("UPDATE " + TABLE + " SET claim_token = NULL, claimed_by = NULL, claimed_at = NULL WHERE claim_token = ?")) {
            ps.setString(1, token);
            return ps.executeUpdate();
        }
    }

    /** Supprime les commandes libres en file depuis avant {@code olderThan} (ms) et rend leur nombre par joueur. */
    List<Purged> purge(long olderThan) throws SQLException {
        List<Purged> out = new ArrayList<>();
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("SELECT uuid, MAX(name), COUNT(*) FROM " + TABLE
                    + " WHERE claim_token IS NULL AND queued_at < ? GROUP BY uuid")) {
                    ps.setLong(1, olderThan);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(new Purged(rs.getString(1), rs.getString(2), rs.getInt(3)));
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + TABLE + " WHERE claim_token IS NULL AND queued_at < ?")) {
                    ps.setLong(1, olderThan);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
        return out;
    }

    List<Stale> stale(long olderThan) throws SQLException {
        List<Stale> out = new ArrayList<>();
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement("SELECT id, uuid, name, command, claimed_by, claimed_at FROM " + TABLE
                 + " WHERE claim_token IS NOT NULL AND claimed_at < ?")) {
            ps.setLong(1, olderThan);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Stale(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6)));
                }
            }
        }
        return out;
    }

    /**
     * Pseudo exact d'un joueur déjà venu sur le réseau, lu dans la table des premières connexions (SHPlayerTrack :
     * colonnes name et display_name), ou null. Une table absente ou illisible rend null.
     */
    String firstSeen(String table, String name) {
        if (table == null || table.isBlank() || !TABLE_NAME.matcher(table).matches()) {
            return null;
        }
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("SELECT display_name FROM " + table + " WHERE name = ? LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }
}
