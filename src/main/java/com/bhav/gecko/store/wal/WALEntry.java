package com.bhav.gecko.store.wal;

import com.bhav.gecko.store.memtable.Record;

public class WALEntry {
    private Operation operation;
    private Record record;
    private long sequenceNumber; // Optional: for ordering

    public WALEntry(Operation operation, Record record) {
        this.operation = operation;
        this.record = record;
        this.sequenceNumber = System.currentTimeMillis();
    }

    public Operation getOperation() {
        return operation;
    }

    public Record getRecord() {
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
