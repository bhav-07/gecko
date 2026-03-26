package com.bhav.gecko.store.wal;

import com.bhav.gecko.store.memtable.MemTableRecord;

public class WALEntry {
    private Operation operation;
    private MemTableRecord record;
    private long sequenceNumber; // Optional: for ordering

    public WALEntry(Operation operation, MemTableRecord record) {
        this.operation = operation;
        this.record = record;
        this.sequenceNumber = System.currentTimeMillis();
    }

    public Operation getOperation() {
        return operation;
    }

    public MemTableRecord getRecord() {
        return record;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    @Override
    public String toString() {
        return String.format("WALEntry{op=%s, key=%s, timestamp=%d}",
                operation, record.getKey(), sequenceNumber);
    }
}
