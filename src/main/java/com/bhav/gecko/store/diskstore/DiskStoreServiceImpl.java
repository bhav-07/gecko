package com.bhav.gecko.store.diskstore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.bhav.gecko.dto.MemtableStats;
import com.bhav.gecko.exception.KeyNotFoundException;
import com.bhav.gecko.service.DiskStoreService;
import com.bhav.gecko.store.memtable.Memtable;
import com.bhav.gecko.store.memtable.Record;
import com.bhav.gecko.store.wal.Operation;
import com.bhav.gecko.store.wal.WALEntry;
import com.bhav.gecko.store.wal.WriteAheadLog;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

// TODO: Implement the flush method
@Service
public class DiskStoreServiceImpl implements DiskStoreService {

    @Autowired
    private Memtable memtable;
    private WriteAheadLog wal;
    private static final Log logger = LogFactory.getLog(DiskStoreServiceImpl.class);
    private List<Memtable> immutableMemtables = new ArrayList<>();

    @Value("${memtable.flsuh.threshold}")
    private int MEMTABLE_FLUSH_THRESHOLD;

    @Value("${wal.directory}")
    private String WAL_DIR;

    @PostConstruct
    public void initialize() {
        try {
            this.wal = new WriteAheadLog(WAL_DIR); // safe now, WAL_DIR is injected
            recoverFromWAL();
            logger.debug("Memtable service initialized with WAL recovery");
        } catch (Exception e) {
            logger.error("WAL recovery failed: " + e.getMessage());
            throw new RuntimeException("Critical: WAL recovery failed", e);
        }
    }

    public Map<String, Record> getAllKVPairs() {
        return memtable.getAllKVPairs();
    }

    public Set<String> getAllKeys() {
        return memtable.getKeys();
    }

    public MemtableStats getMemtableStats() {
        return memtable.getStats();
    }

    public void put(String key, String value) throws Exception {

        Record record = new Record(key, value);

        wal.appendWALOperation(Operation.PUT, record);
        memtable.put(key, record);

        if (memtable.getSizeInBytes() >= MEMTABLE_FLUSH_THRESHOLD) {
            logger.warn("Flush threshold for memtable exceeded!");
            logger.info(memtable.toString());
            immutableMemtables.add(memtable);
            // memtable.clear();
            // wal.truncateWAL();
        }
    }

    public void flushMemtable() {
        for (Memtable memtable : immutableMemtables) {

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

    public Record get(String key) throws KeyNotFoundException {
        return memtable.get(key);
    }

    public void delete(String key) throws Exception {
        Record existingRecord = memtable.get(key);
        if (existingRecord == null) {
            throw new KeyNotFoundException("Cannot delete - key not found: " + key);
        }

        Record tombstoneRecord = new Record(key, existingRecord.getValue(),
                existingRecord.getHeader().getTimeStamp(), true);

        wal.appendWALOperation(Operation.DELETE, tombstoneRecord);
        memtable.delete(key, tombstoneRecord.getRecordSize());
    }

    public void recoverFromWAL() throws Exception {
        if (!wal.hasRecoveryData()) {
            logger.debug("No WAL data found");
            return;
        }

        logger.debug("Starting WAL recovery...");
        List<WALEntry> entries = wal.readAllEntries();

        int appliedEntries = 0;
        int skippedEntries = 0;

        for (WALEntry entry : entries) {
            try {
                applyWALEntry(entry);
                appliedEntries++;
            } catch (Exception e) {
                logger.error("Failed to apply WAL entry: " + entry + " - " + e.getMessage());
                skippedEntries++;
            }
        }

        logger.debug(String.format("WAL recovery completed. Applied: %d, Skipped:%d", appliedEntries, skippedEntries));

        // TODO: Dont truncate here, because if the data isnt written to sstable it can
        // lead to data loss
        // only truncate when we flush to memtable
        // wal.truncateWAL();
    }

    private void applyWALEntry(WALEntry entry) throws Exception {
        Operation op = entry.getOperation();
        Record record = entry.getRecord();

        switch (op) {
            case PUT:
                memtable.put(record.getKey(), record);
                break;

            case DELETE:
                memtable.delete(record.getKey(), record.getRecordSize());
                break;

            case GET:
                // GET operations typically aren't replayed during recovery
                // since they don't modify state
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
        try {
            wal.close();
        } catch (IOException e) {
            System.err.println("Error closing WAL: " + e.getMessage());
        }
    }
}
