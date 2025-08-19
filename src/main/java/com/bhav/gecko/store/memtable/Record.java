package com.bhav.gecko.store.memtable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

public class Record {
    private Header header;
    private String key;
    private String value;
    private int recordSize;

    public Record() {
        this.header = new Header();
    }

    public Record(String key, String value) {
        this.key = key;
        this.value = value;
        this.header = new Header();

        this.header.setTimeStamp((int) (System.currentTimeMillis() / 1000));
        this.header.setTombstone((byte) 0);
        this.header.setKeySize(key.getBytes().length);
        this.header.setValueSize(value.getBytes().length);

        // Calculate record size
        this.recordSize = Header.HEADER_SIZE + key.getBytes().length + value.getBytes().length;

        // Calculate and set checksum
        try {
            int checksum = calculateChecksum();
            this.header.setCheckSum(checksum);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate checksum", e);
        }
    }

    public Record(String key, String value, int timestamp, boolean deleted) {
        this.key = key;
        this.value = value;
        this.header = new Header();

        this.header.setTimeStamp(timestamp);
        this.header.setTombstone(deleted ? (byte) 1 : (byte) 0);
        this.header.setKeySize(key.getBytes().length);
        this.header.setValueSize(value.getBytes().length);

        this.recordSize = Header.HEADER_SIZE + key.getBytes().length + value.getBytes().length;

        try {
            int checksum = calculateChecksum();
            this.header.setCheckSum(checksum);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate checksum", e);
        }
    }

    public byte[] encodeKV() throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(recordSize);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Encode header
        byte[] headerBytes = header.encodeHeader();
        buffer.put(headerBytes);

        // Encode key and value
        buffer.put(key.getBytes());
        buffer.put(value.getBytes());

        return buffer.array();
    }

    public static Record decodeKV(byte[] buf) throws Exception {
        if (buf.length < Header.HEADER_SIZE) {
            throw new Exception("Buffer too small for record");
        }

        Record record = new Record();

        // Decode header
        record.header.decodeHeader(buf);

        int keySize = record.header.getKeySize();
        int valueSize = record.header.getValueSize();

        if (buf.length < Header.HEADER_SIZE + keySize + valueSize) {
            throw new Exception("Buffer too small for complete record");
        }

        // Extract key and value
        record.key = new String(buf, Header.HEADER_SIZE, keySize);
        record.value = new String(buf, Header.HEADER_SIZE + keySize, valueSize);
        record.recordSize = Header.HEADER_SIZE + keySize + valueSize;

        return record;
    }

    public int calculateChecksum() throws Exception {
        // Create buffer for checksum calculation (excluding the checksum field itself)
        ByteBuffer buffer = ByteBuffer.allocate(13 + key.getBytes().length + value.getBytes().length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.put(header.getTombstone());
        buffer.putInt(header.getTimeStamp());
        buffer.putInt(header.getKeySize());
        buffer.putInt(header.getValueSize());

        buffer.put(key.getBytes());
        buffer.put(value.getBytes());

        CRC32 crc32 = new CRC32();
        crc32.update(buffer.array());

        return (int) crc32.getValue();
    }

    public boolean verifyChecksum() throws Exception {
        int calculatedChecksum = calculateChecksum();
        return calculatedChecksum == header.getCheckSum();
    }

    public void markAsDeleted() {
        header.markTombstone();
        // Recalculate checksum after marking as deleted
        try {
            int newChecksum = calculateChecksum();
            header.setCheckSum(newChecksum);
        } catch (Exception e) {
            throw new RuntimeException("Failed to recalculate checksum after deletion", e);
        }
    }

    public int size() {
        return recordSize;
    }

    public Header getHeader() {
        return header;
    }

    public void setHeader(Header header) {
        this.header = header;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
        if (header != null) {
            header.setKeySize(key.getBytes().length);
            updateRecordSize();
        }
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
        if (header != null) {
            header.setValueSize(value.getBytes().length);
            updateRecordSize();
        }
    }

    public int getRecordSize() {
        return recordSize;
    }

    public long getTimestamp() {
        return header != null ? header.getTimeStamp() * 1000L : 0; // Convert to milliseconds
    }

    public boolean isDeleted() {
        return header != null && header.isTombstone();
    }

    private void updateRecordSize() {
        if (key != null && value != null) {
            this.recordSize = Header.HEADER_SIZE + key.getBytes().length + value.getBytes().length;
        }
    }

    @Override
    public String toString() {
        return String.format("Record{key='%s', value='%s', timestamp=%d, deleted=%s, checksum=%d}",
                key, value, getTimestamp(), isDeleted(),
                header != null ? header.getCheckSum() : 0);
    }
}