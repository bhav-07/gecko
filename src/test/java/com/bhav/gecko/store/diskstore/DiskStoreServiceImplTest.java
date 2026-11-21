package com.bhav.gecko.store.diskstore;

import com.bhav.gecko.exception.KeyNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DiskStoreServiceImplTest {

    @TempDir
    Path tempDir;

    private DiskStoreServiceImpl newStore(int flushThresholdBytes, int compactionThreshold) {
        DiskStoreServiceImpl store = new DiskStoreServiceImpl(new SimpleMeterRegistry());
        ReflectionTestUtils.setField(store, "SST_DIR", tempDir.resolve("sst").toString());
        ReflectionTestUtils.setField(store, "WAL_DIR", tempDir.resolve("wal").toString());
        ReflectionTestUtils.setField(store, "walSyncIntervalMs", 50L);
        ReflectionTestUtils.setField(store, "MEMTABLE_FLUSH_THRESHOLD", flushThresholdBytes);
        ReflectionTestUtils.setField(store, "CACHE_ENABLED", true);
        ReflectionTestUtils.setField(store, "CACHE_CAPACITY", 100);
        ReflectionTestUtils.setField(store, "COMPACTION_THRESHOLD", compactionThreshold);
        store.initialize();
        return store;
    }

    private static void shutdownAndWait(DiskStoreServiceImpl store) throws InterruptedException {
        store.cleanup();
        ExecutorService flush = (ExecutorService) ReflectionTestUtils.getField(store, "flushExecutor");
        ExecutorService compaction = (ExecutorService) ReflectionTestUtils.getField(store, "compactionExecutor");
        assertTrue(flush.awaitTermination(60, TimeUnit.SECONDS));
        assertTrue(compaction.awaitTermination(60, TimeUnit.SECONDS));
    }

    /**
     * Each thread owns its keys, so the expected value of every read is known.
     * A tiny flush threshold forces many memtable swaps while threads read their
     * own writes back, which exercises the flush hand-off (I1) and the ordering of
     * pending memtables (I2). Compaction is disabled because the shared file
     * pointer (I5) and merge ordering (C1) bugs are not fixed yet.
     */
    @Test
    void readsYourWritesAcrossManyFlushesAndSurvivesRestart() throws Exception {
        int threads = 6, keysPerThread = 25, rounds = 40;
        DiskStoreServiceImpl store = newStore(2048, 1_000_000);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                for (int r = 0; r < rounds; r++) {
                    for (int k = 0; k < keysPerThread; k++) {
                        String key = "t" + tid + "-k" + k;
                        String value = "r" + r;
                        store.put(key, value);
                        assertEquals(value, store.get(key).getValue(), "read-your-writes for " + key);
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        @SuppressWarnings("unchecked")
        List<Object> sstables = (List<Object>) ReflectionTestUtils.getField(store, "sstables");
        assertTrue(sstables.size() > 5, "test should have produced several flushes, got " + sstables.size());

        String last = "r" + (rounds - 1);
        for (int t = 0; t < threads; t++) {
            for (int k = 0; k < keysPerThread; k++) {
                assertEquals(last, store.get("t" + t + "-k" + k).getValue());
            }
        }
        shutdownAndWait(store);

        // Restart on the same directories: every key must still have its last value.
        DiskStoreServiceImpl restarted = newStore(2048, 1_000_000);
        for (int t = 0; t < threads; t++) {
            for (int k = 0; k < keysPerThread; k++) {
                assertEquals(last, restarted.get("t" + t + "-k" + k).getValue());
            }
        }
        shutdownAndWait(restarted);
    }

    @Test
    void deletesSurviveFlushAndRestart() throws Exception {
        DiskStoreServiceImpl store = newStore(512, 1_000_000);
        for (int i = 0; i < 50; i++) {
            store.put("key" + i, "value" + i);
        }
        store.delete("key7");
        for (int i = 50; i < 100; i++) {
            store.put("key" + i, "value" + i);
        }
        assertThrows(KeyNotFoundException.class, () -> store.get("key7"));
        shutdownAndWait(store);

        DiskStoreServiceImpl restarted = newStore(512, 1_000_000);
        assertThrows(KeyNotFoundException.class, () -> restarted.get("key7"));
        assertEquals("value8", restarted.get("key8").getValue());
        shutdownAndWait(restarted);
    }

    @Test
    void startupFailsWhenManifestListsAMissingSSTable() throws Exception {
        Path sstDir = tempDir.resolve("sst");
        Files.createDirectories(sstDir);
        Files.writeString(sstDir.resolve("gecko.manifest"), "ADD:99:1700000000000\n");

        RuntimeException e = assertThrows(RuntimeException.class, () -> newStore(4096, 4));
        assertTrue(e.getCause().getMessage().contains("sst_99"), e.getCause().getMessage());
        assertFalse(Files.exists(sstDir.resolve("sst_99.data")), "startup must not create an empty table");
    }
}
