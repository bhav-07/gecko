package com.bhav.gecko.store.memtable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Header {
    public static final int HEADER_SIZE = 17;

    private int checkSum;
    private byte tombstone;
    private int timeStamp;
    private int keySize;
    private int valueSize;

    public Header() {
    }

    public Header(int checkSum, byte tombstone, int timeStamp, int keySize, int valueSize) {
        this.checkSum = checkSum;
        this.tombstone = tombstone;
        this.timeStamp = timeStamp;
        this.keySize = keySize;
        this.valueSize = valueSize;
    }

    public static Header fromBytes(byte[] buf) throws Exception {
        if (buf.length < HEADER_SIZE) {
            throw new Exception("Buffer too small for header");
        }

        Header header = new Header();
        header.decodeHeader(buf);
        return header;
    }

    public byte[] encodeHeader() throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(checkSum);
        buffer.put(tombstone);
        buffer.putInt(timeStamp);
        buffer.putInt(keySize);
        buffer.putInt(valueSize);

        return buffer.array();
    }

    public void decodeHeader(byte[] buf) throws Exception {
        if (buf.length < HEADER_SIZE) {
            throw new Exception("Buffer too small for header decoding");
        }

        ByteBuffer buffer = ByteBuffer.wrap(buf, 0, HEADER_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        this.checkSum = buffer.getInt();
        this.tombstone = buffer.get();
        this.timeStamp = buffer.getInt();
        this.keySize = buffer.getInt();
        this.valueSize = buffer.getInt();
    }

    public void markTombstone() {
        this.tombstone = 1;
    }

    public boolean isTombstone() {
        return this.tombstone == 1;
    }

    public int getCheckSum() {
        return checkSum;
    }

    public void setCheckSum(int checkSum) {
        this.checkSum = checkSum;
    }

    public byte getTombstone() {
        return tombstone;
    }

    public void setTombstone(byte tombstone) {
        this.tombstone = tombstone;
    }

    public int getTimeStamp() {
        return timeStamp;
    }

    public void setTimeStamp(int timeStamp) {
        this.timeStamp = timeStamp;
    }

    public int getKeySize() {
        return keySize;
    }

    public void setKeySize(int keySize) {
        this.keySize = keySize;
    }

    public int getValueSize() {
        return valueSize;
    }

    public void setValueSize(int valueSize) {
        this.valueSize = valueSize;
    }
}