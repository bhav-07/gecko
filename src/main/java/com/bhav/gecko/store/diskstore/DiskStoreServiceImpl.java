package com.bhav.gecko.store.diskstore;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.bhav.gecko.store.cache.LRUCache;
import com.bhav.gecko.store.cache.NoOpCache;
import com.bhav.gecko.store.cache.ReadCache;
import com.bhav.gecko.store.compaction.CompactionEngine;
import com.bhav.gecko.store.compaction.MergeAllStrategy;
import com.bhav.gecko.store.sstable.SSTable;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.bhav.gecko.store.manifest.Manifest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.bhav.gecko.dto.MemtableStats;
import com.bhav.gecko.exception.KeyNotFoundException;
import com.bhav.gecko.exception.StorageException;
import com.bhav.gecko.service.DiskStoreService;
import com.bhav.gecko.store.memtable.Memtable;
import com.bhav.gecko.store.memtable.MemTableRecord;
import com.bhav.gecko.store.wal.Operation;
import com.bhav.gecko.store.wal.WALEntry;
import com.bhav.gecko.store.wal.WriteAheadLog;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

// TODO: Implement the flush method
@Service
public class DiskStoreServiceImpl implements DiskStoreService {

    private volatile Memtable memtable = new Memtable();
    private WriteAheadLog activeWal;
    private Manifest manifest;
    private static final Log logger = LogFactory.getLog(DiskStoreServiceImpl.class);
    private final List<Memtable> immutableMemtables = new CopyOnWriteArrayList<>();
    private final ExecutorService flushExecutor = Executors.newSingleThreadExecutor();
    private final List<SSTable> sstables = new CopyOnWriteArrayList<>();
    private final Object flushLock = new Object();
    private final Object compactionLock = new Object();
    private final ExecutorService compactionExecutor = Executors.newSingleThreadExecutor();
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private ReadCache readCache;
    private CompactionEngine compactionEngine;

    @Value("${sst.directory}")
    private String SST_DIR;

    @Value("${wal.directory}")
    private String WAL_DIR;

    @Value("${wal.sync.interval.ms:100}")
    private long walSyncIntervalMs;

    private ScheduledExecutorService walSyncExecutor;

    @Value("${memtable.flsuh.threshold}")
    private int MEMTABLE_FLUSH_THRESHOLD;

    @Value("${read.cache.enabled:true}")
    private boolean CACHE_ENABLED;

    @Value("${read.cache.capacity:1000}")
    private int CACHE_CAPACITY;

    @Value("${compaction.threshold:4}")
    private int COMPACTION_THRESHOLD;

    private final MeterRegistry meterRegistry;

    public DiskStoreServiceImpl(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        // Gauge for memtable size
        meterRegistry.gauge("gecko.memtable.size.bytes", this, svc -> svc.memtable.getSizeInBytes());
    }

    @PostConstruct
    public void initialize() {
        try {
            // 1. Initialize the read cache (LRUCache if enabled, NoOpCache if disabled)
            this.readCache = CACHE_ENABLED
                    ? new LRUCache(CACHE_CAPACITY)
                    : new NoOpCache();

            // 2. Load manifest and recover active SSTables from disk
            this.manifest = new Manifest(SST_DIR);
            manifest.load();
            loadSSTTablesFromManifest();

            // 3. Initialize the compaction engine with the configured strategy.
            // Must come after manifest is loaded so the engine has a valid manifest reference.
            this.compactionEngine = new CompactionEngine(
                    SST_DIR,
                    manifest,
                    new MergeAllStrategy(COMPACTION_THRESHOLD));

            // 4. Replay WAL to restore any writes that hadn't been flushed yet
            recoverFromWAL();

            // 5. Create a fresh WAL segment for this session
            this.activeWal = WriteAheadLog.createSegment(WAL_DIR);

            // 6. Start the WAL sync background thread
            this.walSyncExecutor = Executors.newSingleThreadScheduledExecutor();
            this.walSyncExecutor.scheduleAtFixedRate(() -> {
                try {
                    WriteAheadLog currentWal;
                    rwLock.readLock().lock();
                    try {
                        currentWal = this.activeWal;
                    } finally {
                        rwLock.readLock().unlock();
                    }
                    if (currentWal != null) {
                        currentWal.sync();
                    }
                } catch (Exception e) {
                    logger.error("Error during background WAL sync", e);
                }
            }, walSyncIntervalMs, walSyncIntervalMs, TimeUnit.MILLISECONDS);

            logger.info("Initialized: " + sstables.size() + " SSTables loaded");
        } catch (Exception e) {
            logger.error("Initialization failed: " + e.getMessage());
            throw new RuntimeException("Critical: Initialization failed", e);
        }
    }

    /**
     * Loads all active SSTables from the manifest into memory.
     * Only bloom filters and sparse indexes are loaded — the actual .data
     * files stay on disk and are accessed via seek() during reads.
     */
    private void loadSSTTablesFromManifest() throws IOException {
        List<Integer> activeSSTIds = manifest.getActiveSSTIds();

        // Sync the SSTable counter so new flushes don't collide with existing files
        int maxId = manifest.getMaxSSTId();
        SSTable.syncCounter(maxId);

        for (int sstId : activeSSTIds) {
            try {
                SSTable sst = SSTable.loadFromDisk(SST_DIR, sstId);
                // Add to the front so the list stays newest-first (add(0,...) convention)
                sstables.add(0, sst);
                logger.info("Loaded SSTable from disk: sst_" + sstId);
            } catch (IOException e) {
                // Refuse to start. The manifest says this table holds committed data, and
                // its WAL segment was deleted when it was flushed, so there is no other
                // copy. Skipping it would silently serve older values or lose keys.
                throw new IOException("Manifest lists sst_" + sstId
                        + " but it could not be loaded: " + e.getMessage(), e);
            }
        }

        logger.info("SSTable recovery complete: " + sstables.size() + " tables loaded");
    }

    public Map<String, MemTableRecord> getAllKVPairs() {
        return memtable.getAllKVPairs();
    }

    public Set<String> getAllKeys() {
        return memtable.getKeys();
    }

    public MemtableStats getMemtableStats() {
        return memtable.getStats();
    }

    public void put(String key, String value) throws Exception {

        MemTableRecord record = new MemTableRecord(key, value);

        rwLock.readLock().lock();
        try {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                activeWal.appendWALOperation(Operation.PUT, record);
            } finally {
                sample.stop(meterRegistry.timer("gecko.wal.append.latency"));
            }
            memtable.put(key, record);
            readCache.invalidate(key);
        } finally {
            rwLock.readLock().unlock();
        }

        checkAndInitiateFlush();
    }

    private void checkAndInitiateFlush() {
        if (memtable.getSizeInBytes() >= MEMTABLE_FLUSH_THRESHOLD) {
            synchronized (flushLock) {
                if (memtable.getSizeInBytes() >= MEMTABLE_FLUSH_THRESHOLD) {
                    logger.warn("Flush threshold exceeded (Size: " + memtable.getSizeInBytes() + " bytes) - initiating flush");
                    logger.info(memtable.toString());
                    
                    rwLock.writeLock().lock();
                    WriteAheadLog frozenWAL;
                    Memtable frozenMemtable;
                    try {
                        // Create the new segment first so a failure leaves everything unchanged.
                        WriteAheadLog newWal = WriteAheadLog.createSegment(WAL_DIR);
                        frozenWAL = this.activeWal;
                        frozenMemtable = this.memtable;

                        // Publish the frozen memtable BEFORE swapping in the empty one.
                        // get() doesn't take the lock: it reads the active memtable, then the
                        // immutable list. With this order a reader always finds the key in one
                        // of the two. Index 0 keeps the list newest-first, so a key written in
                        // two pending memtables resolves to the newer value.
                        immutableMemtables.add(0, frozenMemtable);

                        this.activeWal = newWal;
                        this.memtable = new Memtable();
                    } catch (IOException e) {
                        logger.error("Failed to create new WAL segment during flush", e);
                        return; // Abort flush if we can't create a new WAL
                    } finally {
                        rwLock.writeLock().unlock();
                    }

                    // flushImmutable() removes the frozen memtable from the list once it's safely on disk.
                    flushExecutor.submit(() -> flushImmutable(frozenMemtable, frozenWAL));
                }
            }
        }
    }

    private void flushImmutable(Memtable toFlush, WriteAheadLog segmentWAL) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            logger.info("Background flush task started for memtable (" + toFlush.size() + " entries)");
            List<MemTableRecord> entries = new ArrayList<>(toFlush.getAllKVPairs().values());

            // 1. Write SSTable files to disk (.data, .index, .bloom)
            SSTable sst = SSTable.initSSTableOnDisk(entries, SST_DIR);

            // 2. Record in manifest, this is the commit point.
            // After this line, the SSTable is officially part of the database
            // and will be recovered on the next restart.
            manifest.addEntry(sst.getSstCounter());

            // 3. Add to in-memory SSTable list for reads (at front = newest)
            sstables.add(0, sst);

            // 3b. Drop cached entries for every flushed key. A reader racing an earlier
            // put() can cache the value it found in an older SSTable after put() already
            // invalidated the key. That entry is harmless while the key is in a memtable
            // (checked before the cache) but would be served once the memtable is gone.
            // This must run before step 4: while the frozen memtable is still listed,
            // readers find the key there and never consult the cache.
            for (String key : toFlush.getKeys()) {
                readCache.invalidate(key);
            }

            // 4. Remove from immutable list, no longer needed for read path
            immutableMemtables.remove(toFlush);

            // 5. Delete WAL segment, data is safely on disk and in the manifest
            segmentWAL.deleteSegment();

            logger.info("Flush complete: sst_" + sst.getSstCounter());

            // 6. Check if compaction should run. Submit a task to the compaction executor.
            // The snapshot is taken INSIDE the task so it reflects the most up-to-date
            // state of the list when the task actually starts running. If a previous
            // compaction just finished, this task will see the new merged SSTable.
            compactionExecutor.submit(() -> {
                List<SSTable> currentSnapshot = new ArrayList<>(sstables);
                compactionEngine.maybeCompact(currentSnapshot, this::swapSSTables);
            });

        } catch (Exception e) {
            // segmentWAL stays on disk — recoverable after crash
            logger.error("Flush failed: " + e.getMessage(), e);
        } finally {
            sample.stop(meterRegistry.timer("gecko.flush.duration"));
        }
    }

    /**
     * Atomically replaces the old SSTables with the new merged one in the
     * live sstables list. Called by CompactionEngine after a successful merge.
     *
     * Synchronized so no two compactions can race on the list simultaneously.
     * CopyOnWriteArrayList ensures readers currently iterating a snapshot of
     * the old list finish safely without seeing a half-swapped state.
     */
    private void swapSSTables(List<SSTable> oldSSTables, SSTable newSSTable) {
        synchronized (compactionLock) {
            sstables.add(0, newSSTable);
            sstables.removeAll(oldSSTables);
            logger.info("SSTable swap complete: " + oldSSTables.size()
                    + " old SSTables replaced by sst_" + newSSTable.getSstCounter()
                    + " | active SSTables: " + sstables.size());
        }
    }

    /**
     * Writes each pair with its own put(). This is NOT atomic: if some keys fail, the
     * others stay written. Failures are reported to the caller instead of being hidden.
     */
    public void bulkInsert(Map<String, String> data) throws Exception {
        List<String> failedKeys = new ArrayList<>();
        Exception firstFailure = null;
        for (Map.Entry<String, String> entry : data.entrySet()) {
            try {
                put(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                logger.error("Failed to insert key: '" + entry.getKey() + "' during bulk insert", e);
                failedKeys.add(entry.getKey());
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        if (!failedKeys.isEmpty()) {
            throw new Exception("Bulk insert partially failed: " + failedKeys.size() + " of "
                    + data.size() + " keys were not written " + failedKeys, firstFailure);
        }
    }

    public MemTableRecord get(String key) throws KeyNotFoundException {
        // 1. Active memtable. Read the volatile field once: a flush can swap it at any
        // moment, and checking one instance but reading another gives a false miss.
        Memtable active = this.memtable;
        if (active.containsKey(key)) {
            return active.get(key);
        }
        // 2. Immutable memtables (data frozen during an in-progress flush)
        for (Memtable imm : immutableMemtables) {
            if (imm.containsKey(key)) {
                return imm.get(key);
            }
        }
        // 3. LRU cache — avoids hitting the disk for recently read keys
        MemTableRecord cached = readCache.get(key);
        if (cached != null) {
            // Tombstones can live in the cache too (from a previous SSTable search)
            if (cached.isDeleted()) {
                throw new KeyNotFoundException("Key has been deleted: " + key);
            }
            return cached;
        }
        // 4. SSTables (disk I/O — most expensive path)
        for (SSTable sst : sstables) {
            try {
                if (!sst.getBloomFilter().mightContain(key)) {
                    continue;
                }
                meterRegistry.counter("gecko.bloom.positives").increment();

                MemTableRecord record = sst.search(key);
                if (record != null) {
                    meterRegistry.counter("gecko.sstable.true_positives").increment();
                    // Cache the result (including tombstones) so repeated lookups skip the disk
                    readCache.put(key, record);
                    if (record.isDeleted()) {
                        throw new KeyNotFoundException("Key has been deleted: " + key);
                    }
                    return record;
                }
            } catch (KeyNotFoundException e) {
                // Tombstone found — stop searching older SSTables and propagate immediately
                throw e;
            } catch (Exception e) {
                // Don't fall through to older SSTables: they may hold an outdated value or
                // a value this table deleted. An error is better than a wrong answer.
                logger.error("Error searching SSTable sst_" + sst.getSstCounter() + " for key: " + key, e);
                throw new StorageException("Failed to read key '" + key + "' from sst_"
                        + sst.getSstCounter(), e);
            }
        }

        throw new KeyNotFoundException("Key not found: " + key);
    }

    public void delete(String key) throws Exception {
        MemTableRecord tombstoneRecord = new MemTableRecord(key, "",
                System.currentTimeMillis(), true);

        rwLock.readLock().lock();
        try {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                activeWal.appendWALOperation(Operation.DELETE, tombstoneRecord);
            } finally {
                sample.stop(meterRegistry.timer("gecko.wal.append.latency"));
            }
            memtable.delete(key, tombstoneRecord);
            readCache.invalidate(key);
        } finally {
            rwLock.readLock().unlock();
        }
        
        checkAndInitiateFlush();
    }

    public void recoverFromWAL() throws Exception {
        File walDir = new File(WAL_DIR);
        if (!walDir.exists())
            return;
        File[] segments = walDir.listFiles((d, name) -> name.startsWith("wal_") && name.endsWith(".log"));
        if (segments == null || segments.length == 0)
            return;
        // Sort by timestamp embedded in filename
        Arrays.sort(segments,
                Comparator.comparingLong(f -> Long.parseLong(f.getName().replace("wal_", "").replace(".log", ""))));
        for (File segment : segments) {
            WriteAheadLog wal = new WriteAheadLog(segment.getAbsolutePath());
            List<WALEntry> entries = wal.readAllEntries();
            for (WALEntry entry : entries) {
                applyWALEntry(entry);
            }
            wal.close();
        }

        // 1. If we recovered any data, flush it immediately to an SSTable
        if (!memtable.isEmpty()) {
            logger.info("Flushing recovered memtable to SSTable...");
            List<MemTableRecord> entries = new ArrayList<>(memtable.getAllKVPairs().values());
            SSTable sst = SSTable.initSSTableOnDisk(entries, SST_DIR);
            manifest.addEntry(sst.getSstCounter());
            sstables.add(0, sst);
            
            // Start fresh
            this.memtable = new Memtable();
        }

        // 2. Now that data is safely on disk (or memtable was empty), delete all old WAL segments
        for (File segment : segments) {
            if (!segment.delete()) {
                logger.warn("Failed to delete recovered WAL segment: " + segment.getName());
            }
        }
        logger.info("WAL recovery complete. Cleaned up " + segments.length + " segments.");
    }

    private void applyWALEntry(WALEntry entry) throws Exception {
        Operation op = entry.getOperation();
        MemTableRecord record = entry.getRecord();

        switch (op) {
            case PUT:
                memtable.put(record.getKey(), record);
                break;

            case DELETE:
                memtable.delete(record.getKey(), record);
                break;

            default:
                throw new Exception("Unknown operation type: " + op);
        }
    }

    @PreDestroy
    public void cleanup() {
        if (walSyncExecutor != null) {
            walSyncExecutor.shutdown();
        }
        flushExecutor.shutdown();
        compactionExecutor.shutdown();
        try {
            if (activeWal != null) {
                activeWal.sync();
                activeWal.close();
            }
        } catch (Exception e) {
            logger.error("Error closing active WAL: " + e.getMessage());
        }
    }
}
