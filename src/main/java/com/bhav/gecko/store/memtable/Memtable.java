package com.bhav.gecko.store.memtable;

import java.io.IOException;
import java.util.*;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.bhav.gecko.exception.KeyNotFoundException;
import com.bhav.gecko.store.wal.Operation;
import com.bhav.gecko.store.wal.WALEntry;
import com.bhav.gecko.store.wal.WriteAheadLog;

// TODO: Implement the flush method
public class Memtable {
    private TreeMap<String, Record> data;
    private int sizeInBytes;
    private WriteAheadLog wal;

    private static final Log logger = LogFactory.getLog(Memtable.class);

    public Memtable(String walDirectory) throws IOException {
        this.data = new TreeMap<>();
        this.sizeInBytes = 0;
        this.wal = new WriteAheadLog(walDirectory);
    }

    public void put(String key, Record value) throws Exception {
        wal.appendWALOperation(Operation.PUT, value);

        Record existingRecord = data.get(key);
        if (existingRecord != null) {
            sizeInBytes -= existingRecord.getRecordSize();
        }
        data.put(key, value);
        sizeInBytes += value.getRecordSize();
    }

    public Record get(String key) throws KeyNotFoundException {
        Record record = data.get(key);
        if (record == null) {
            throw new KeyNotFoundException("Key not found: " + key);
        }
        return record;
    }

    public void delete(String key) throws Exception {
        Record existingRecord = data.get(key);
        if (existingRecord == null) {
            throw new KeyNotFoundException("Cannot delete - key not found: " + key);
        }

        Record tombstoneRecord = new Record(key, existingRecord.getValue(),
                existingRecord.getHeader().getTimeStamp(), true);

        wal.appendWALOperation(Operation.DELETE, tombstoneRecord);

        data.remove(key);
        sizeInBytes -= existingRecord.getRecordSize();
    }

    public Map<String, Record> getAllKVPairs() {
        return new HashMap<>(data);
    }

    public void clear() {
        data.clear();
        sizeInBytes = 0;
    }

    public int getSizeInBytes() {
        return sizeInBytes;
    }

    public int size() {
        return data.size();
    }

    public boolean isEmpty() {
        return data.isEmpty();
    }

    public Set<String> getKeys() {
        return data.keySet();
    }

    public boolean containsKey(String key) {
        return data.containsKey(key);
    }

    public void closeWAL() throws IOException {
        if (wal != null) {
            wal.close();
        }
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

        logger.debug(String.format("WAL recovery completed. Applied: %d, Skipped: %d",
                appliedEntries, skippedEntries));

        wal.truncateWAL();
    }

    private void applyWALEntry(WALEntry entry) throws Exception {
        Operation op = entry.getOperation();
        Record record = entry.getRecord();

        switch (op) {
            case PUT:
                applyPutWithoutLogging(record.getKey(), record);
                break;

            case DELETE:
                applyDeleteWithoutLogging(record.getKey());
                break;

            case GET:
                // GET operations typically aren't replayed during recovery
                // since they don't modify state
                break;

            default:
                throw new Exception("Unknown operation type: " + op);
        }
    }

    private void applyPutWithoutLogging(String key, Record record) {
        Record existingRecord = data.get(key);
        if (existingRecord != null) {
            sizeInBytes -= existingRecord.getRecordSize();
        }
        data.put(key, record);
        sizeInBytes += record.getRecordSize();
    }

    private void applyDeleteWithoutLogging(String key) {
        Record existingRecord = data.get(key);
        if (existingRecord != null) {
            data.remove(key);
            sizeInBytes -= existingRecord.getRecordSize();
        }
    }
}