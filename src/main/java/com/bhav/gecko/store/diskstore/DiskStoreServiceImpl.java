package com.bhav.gecko.store.diskstore;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.bhav.gecko.store.sstable.SSTable;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.bhav.gecko.store.manifest.Manifest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.bhav.gecko.dto.MemtableStats;
import com.bhav.gecko.exception.KeyNotFoundException;
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

    @Value("${sst.directory}")
    private String SST_DIR;

    @Value("${wal.directory}")
    private String WAL_DIR;

    @Value("${memtable.flsuh.threshold}")
    private int MEMTABLE_FLUSH_THRESHOLD;

    private final MeterRegistry meterRegistry;

    public DiskStoreServiceImpl(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        // Gauge for memtable size
        meterRegistry.gauge("gecko.memtable.size.bytes", this, svc -> svc.memtable.getSizeInBytes());
    }

    @PostConstruct
    public void initialize() {
        try {
            // 1. Load manifest and recover active SSTables from disk
            this.manifest = new Manifest(SST_DIR);
            manifest.load();
            loadSSTTablesFromManifest();

            // 2. Replay WAL to restore any writes that hadn't been flushed yet
            recoverFromWAL();

            // 3. Create a fresh WAL segment for this session
            this.activeWal = WriteAheadLog.createSegment(WAL_DIR);
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
                // Log but don't crash - a corrupted SSTable shouldn't bring down the server.
                // The data is still in the WAL if it hadn't been flushed cleanly.
                logger.error("Failed to load sst_" + sstId + " — skipping: " + e.getMessage());
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

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            activeWal.appendWALOperation(Operation.PUT, record);
        } finally {
            sample.stop(meterRegistry.timer("gecko.wal.append.latency"));
        }
        memtable.put(key, record);

        if (memtable.getSizeInBytes() >= MEMTABLE_FLUSH_THRESHOLD) {
            synchronized (flushLock) {
                if (memtable.getSizeInBytes() >= MEMTABLE_FLUSH_THRESHOLD) {
                    logger.warn("Flush threshold exceeded (Size: " + memtable.getSizeInBytes() + " bytes) - initiating flush");
                    logger.info(memtable.toString());
                    WriteAheadLog frozenWAL = this.activeWal;
                    Memtable frozenMemtable = this.memtable;

                    this.activeWal = WriteAheadLog.createSegment(WAL_DIR);
                    this.memtable = new Memtable();

                    // Track the frozen memtable so reads during the flush window still find its
                    // keys.
                    // flushImmutable() will remove it from this list once it's safely on disk.
                    immutableMemtables.add(frozenMemtable);
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

            // 4. Remove from immutable list, no longer needed for read path
            immutableMemtables.remove(toFlush);

            // 5. Delete WAL segment, data is safely on disk and in the manifest
            segmentWAL.deleteSegment();

            logger.info("Flush complete: sst_" + sst.getSstCounter());
        } catch (Exception e) {
            // segmentWAL stays on disk — recoverable after crash
        } finally {
            sample.stop(meterRegistry.timer("gecko.flush.duration"));
        }
    }

    public void bulkInsert(Map<String, String> data) throws Exception {
        data.forEach((t, u) -> {
            try {
                put(t, u);
            } catch (Exception e) {
                logger.error("Failed to insert key: '" + t + "' during bulk insert", e);
            }
        });
    }

    public MemTableRecord get(String key) throws KeyNotFoundException {
        // Active memtable
        if (memtable.containsKey(key)) {
            return memtable.get(key);
        }
        // Immutable memtables
        for (Memtable imm : immutableMemtables) {
            if (imm.containsKey(key)) {
                return imm.get(key);
            }
        }
        // SSTables
        for (SSTable sst : sstables) {
            try {
                if (!sst.getBloomFilter().mightContain(key)) {
                    continue;
                }
                meterRegistry.counter("gecko.bloom.positives").increment();
                
                MemTableRecord record = sst.search(key); // The internal search still does a redundant bloom check, but that's fast.
                if (record != null) {
                    meterRegistry.counter("gecko.sstable.true_positives").increment();
                    if (record.isDeleted()) {
                        throw new KeyNotFoundException("Key has been deleted: " + key);
                    }
                    return record;
                }
            } catch (KeyNotFoundException e) {
                // If we found a tombstone, stop searching older SSTables and propagate the delete immediately
                throw e;
            } catch (Exception e) {
                logger.error("Error searching SSTable sst_" + sst.getSstCounter() + " for key: " + key, e);
            }
        }

        throw new KeyNotFoundException("Key not found: " + key);
    }

    public void delete(String key) throws Exception {
        MemTableRecord tombstoneRecord = new MemTableRecord(key, "",
                System.currentTimeMillis(), true);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            activeWal.appendWALOperation(Operation.DELETE, tombstoneRecord);
        } finally {
            sample.stop(meterRegistry.timer("gecko.wal.append.latency"));
        }
        memtable.delete(key, tombstoneRecord);
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
        flushExecutor.shutdown();
        try {
            if (activeWal != null)
                activeWal.close();
        } catch (IOException e) {
            logger.error("Error closing active WAL: " + e.getMessage());
        }
    }
}
