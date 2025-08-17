package com.bhav.gecko.store;

public class Record {
    private String key;
    private String value;
    private long timestamp;
    private boolean deleted;
    private int recordSize;

    public Record(String key, String value) {
        this.key = key;
        this.value = value;
        this.timestamp = System.currentTimeMillis();
        this.deleted = false;
        this.recordSize = calculateRecordSize();
    }

    public Record(String key, String value, long timestamp, boolean deleted) {
        this.key = key;
        this.value = value;
        this.timestamp = timestamp;
        this.deleted = deleted;
        this.recordSize = calculateRecordSize();
    }

    private int calculateRecordSize() {
        // Calculate approximate size: key + value + metadata
        int keySize = key != null ? key.getBytes().length : 0;
        int valueSize = value != null ? value.getBytes().length : 0;
        int metadataSize = 8 + 1 + 4;
        return keySize + valueSize + metadataSize;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
        this.recordSize = calculateRecordSize();
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
        this.recordSize = calculateRecordSize();
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public int getRecordSize() {
        return recordSize;
    }

    @Override
    public String toString() {
        return String.format("Record{key='%s', value='%s', timestamp=%d, deleted=%s}",
                key, value, timestamp, deleted);
    }
}