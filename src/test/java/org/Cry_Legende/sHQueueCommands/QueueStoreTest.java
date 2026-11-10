package org.Cry_Legende.sHQueueCommands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * File commune sur un vrai MariaDB : chaque fil joue un serveur où le joueur arrive au même moment. Ignoré sans
 * SHQC_TEST_URL (base jetable : les tables shqc_* y sont recréées), SHQC_TEST_USER, SHQC_TEST_PASSWORD.
 */
class QueueStoreTest {

    private QueueStore store;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("SHQC_TEST_URL");
        Assumptions.assumeTrue(url != null, "SHQC_TEST_URL absent : test MariaDB ignoré");
        store = new QueueStore(url, System.getenv("SHQC_TEST_USER"), System.getenv("SHQC_TEST_PASSWORD"), 5000);
        try (Connection c = store.connection(); Statement st = c.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS shqc_queue");
            st.executeUpdate("DROP TABLE IF EXISTS shqc_state");
        }
        store.createTables();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    private int count(String sql) throws Exception {
        try (Connection c = store.connection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : futures) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }

    @Test
    void eachCommandIsClaimedByOneServerOnly() throws Exception {
        UUID uuid = UUID.randomUUID();
        for (int i = 0; i < 20; i++) {
            store.insert(uuid, "Joueur", "say commande " + i, System.currentTimeMillis(), "poc1");
        }
        List<Callable<List<QueueStore.Claimed>>> servers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String server = "poc" + i;
            servers.add(() -> store.claim(uuid, server, UUID.randomUUID().toString()));
        }
        List<List<QueueStore.Claimed>> claims = race(servers);
        Set<Long> ids = new HashSet<>();
        int total = 0;
        for (List<QueueStore.Claimed> c : claims) {
            total += c.size();
            c.forEach(x -> ids.add(x.id()));
        }
        assertEquals(20, total, "toutes les commandes réclamées");
        assertEquals(20, ids.size(), "aucune réclamée deux fois");
        assertEquals(0, count("SELECT COUNT(*) FROM shqc_queue WHERE claim_token IS NULL"));
    }

    @Test
    void releasedCommandsComeBackAndDeletedOnesDoNot() throws Exception {
        UUID uuid = UUID.randomUUID();
        store.insert(uuid, "Joueur", "say un", System.currentTimeMillis(), "poc1");
        String first = UUID.randomUUID().toString();
        assertEquals(1, store.claim(uuid, "poc1", first).size());
        assertEquals(0, store.claim(uuid, "poc2", UUID.randomUUID().toString()).size(), "déjà réclamée");
        assertEquals(1, store.release(first), "joueur reparti : rendue");
        String second = UUID.randomUUID().toString();
        assertEquals(1, store.claim(uuid, "poc2", second).size());
        assertEquals(1, store.delete(second));
        assertEquals(0, store.claim(uuid, "poc1", UUID.randomUUID().toString()).size(), "exécutée : plus jamais relancée");
    }

    @Test
    void yamlQueueIsImportedOnceForTheWholeNetwork() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Map<UUID, List<QueuedCommand>> queue = Map.of(
            a, List.of(new QueuedCommand("say a1", 1000L), new QueuedCommand("say a2", 2000L)),
            b, List.of(new QueuedCommand("say b1", 3000L)));
        List<Callable<Integer>> servers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String server = "poc" + i;
            servers.add(() -> store.importOnce(queue, server));
        }
        List<Integer> results = race(servers);
        assertEquals(1, results.stream().filter(n -> n == 3).count(), "un seul serveur importe");
        assertEquals(3, results.stream().filter(n -> n == -1).count());
        assertEquals(3, count("SELECT COUNT(*) FROM shqc_queue"));
        assertEquals(1000L, count("SELECT MIN(queued_at) FROM shqc_queue"), "dates gardées");
    }

    @Test
    void purgeRemovesOldFreeCommandsOnly() throws Exception {
        UUID uuid = UUID.randomUUID();
        store.insert(uuid, "Vieux", "say vieux", 1000L, "poc1");
        store.insert(uuid, "Vieux", "say neuf", System.currentTimeMillis(), "poc1");
        UUID other = UUID.randomUUID();
        store.insert(other, "Pris", "say pris", 1000L, "poc1");
        store.claim(other, "poc2", UUID.randomUUID().toString());
        List<QueueStore.Purged> purged = store.purge(System.currentTimeMillis() - 60_000);
        assertEquals(1, purged.size());
        assertEquals(1, purged.get(0).count());
        assertEquals(2, count("SELECT COUNT(*) FROM shqc_queue"), "la récente et la réclamée restent");
        assertTrue(store.stale(System.currentTimeMillis() + 1000).stream().anyMatch(s -> "Pris".equals(s.name())));
    }
}
