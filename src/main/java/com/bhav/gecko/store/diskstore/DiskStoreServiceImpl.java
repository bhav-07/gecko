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
import org.springframework.beans.factory.annotation.Autowired;
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

// TODO: Implement the flush method
@Service
public class DiskStoreServiceImpl implements DiskStoreService {

    private Memtable memtable = new Memtable();
    private WriteAheadLog activeWal;
    private static final Log logger = LogFactory.getLog(DiskStoreServiceImpl.class);
    private final List<Memtable> immutableMemtables = new CopyOnWriteArrayList<>();
    private final ExecutorService flushExecutor = Executors.newSingleThreadExecutor();
    private final List<SSTable> sstables = new ArrayList<>();

    private final AtomicInteger walSegmentCounter = new AtomicInteger(0);

    @Value("${sst.directory}")
    private String SST_DIR;

    @Value("${wal.directory}")
    private String WAL_DIR;

    @Value("${memtable.flsuh.threshold}")
    private int MEMTABLE_FLUSH_THRESHOLD;

    @PostConstruct
    public void initialize() {
        try {
            recoverFromWAL();
            this.activeWal = WriteAheadLog.createSegment(WAL_DIR);
            logger.info("Initialized: " + sstables.size() + " SSTables loaded");
        } catch (Exception e) {
            logger.error("WAL recovery failed: " + e.getMessage());
            throw new RuntimeException("Critical: WAL recovery failed", e);
        }
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

        activeWal.appendWALOperation(Operation.PUT, record);
        memtable.put(key, record);

        if (memtable.getSizeInBytes() >= MEMTABLE_FLUSH_THRESHOLD) {
            logger.warn("Flush threshold for memtable exceeded!");
            logger.info(memtable.toString());
            WriteAheadLog frozenWAL = this.activeWal;
            Memtable frozenMemtable = this.memtable;

            this.activeWal = WriteAheadLog.createSegment(WAL_DIR);
            this.memtable = new Memtable();
            flushExecutor.submit(() -> flushImmutable(frozenMemtable, frozenWAL));
        }
    }

    private void flushImmutable(Memtable toFlush, WriteAheadLog segmentWAL) {
        try {
            logger.info("Flush initiated for active memtable>>>>>>>>");
            List<MemTableRecord> entries = new ArrayList<>(toFlush.getAllKVPairs().values());
            // Write SSTable files to disk
            long sstId = System.currentTimeMillis();
            SSTable sst = SSTable.initSSTableOnDisk(entries, SST_DIR);
            // TODO: Implement manifest update logic here
            // 2. Update manifest — this is the commit point
            // After this, the SSTable is officially part of the database
            // 3. Add to in-memory SSTable list for reads (at front = newest)
            sstables.add(0, sst);
            // 4. Remove from immutable list — no longer needed for read path
            immutableMemtables.remove(toFlush);
            // 5. Delete WAL segment — data is safely in SSTable now
            segmentWAL.deleteSegment();
            logger.info("Flush complete: sst_" + sstId);
        } catch (Exception e) {
            logger.error("Flush failed, WAL segment kept: " + e.getMessage());
            // toFlush stays in immutableMemtables — still readable
            // segmentWAL stays on disk — recoverable after crash
        }
    }

    public void bulkInsert(Map<String, String> data) throws Exception {
        data.forEach((t, u) -> {
            try {
                put(t, u);
            } catch (Exception e) {
                // TODO: Handle this better
                e.printStackTrace();
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
                MemTableRecord record = sst.search(key);
                if (record != null) {
                    if (record.isDeleted()) {
                        throw new KeyNotFoundException("Key has been deleted: " + key);
                    }
                    return record;
                }
            } catch (Exception e) {
                logger.error("Error searching SSTable for key: " + key, e);
            }
        }
        
        throw new KeyNotFoundException("Key not found: " + key);
    }

    public void delete(String key) throws Exception {
        MemTableRecord existingRecord = this.get(key);

        MemTableRecord tombstoneRecord = new MemTableRecord(key, existingRecord.getValue(),
                existingRecord.getHeader().getTimeStamp(), true);

        activeWal.appendWALOperation(Operation.DELETE, tombstoneRecord);
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

    // TODO: What is even this??
    // public void clear() {
    // try {
    // java.lang.reflect.Method clearMethod =
    // Memtable.class.getDeclaredMethod("clear");
    // clearMethod.setAccessible(true);
    // clearMethod.invoke(memtable);
    // } catch (Exception e) {
    // throw new RuntimeException("Failed to clear memtable", e);
    // }
    // }

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
