package com.bhav.gecko.store.memtable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Represents the fixed-size metadata header that sits at the front of every
 * record stored on disk.
 *
 * <p>Every record written to the WAL, SSTable, or disk store follows this layout:
 * <pre>
 *   [ Header (17 bytes) ][ Key (keySize bytes) ][ Value (valueSize bytes) ]
 * </pre>
 *
 * <p>Reading the header first tells you exactly how many bytes to read next for
 * the key and value, so you never have to guess or scan blindly through the file.
 *
 * <p>The header is serialized in little-endian byte order and always occupies
 * exactly {@value #HEADER_SIZE} bytes on disk.
 */
public class Header {

    /** The number of bytes a serialized header occupies on disk. */
    public static final int HEADER_SIZE = 21; // 4 checksum + 1 tombstone + 8 timestamp + 4 keySize + 4 valueSize

    /** CRC or checksum used to detect data corruption when reading back from disk. */
    private int checkSum;

    /**
     * A single byte that marks whether this record has been deleted.
     * A value of {@code 1} means the record is a tombstone (deleted),
     * and {@code 0} means it is alive.
     */
    private byte tombstone;

    /** Unix timestamp in milliseconds of when this record was written. */
    private long timeStamp;

    /** The length of the key in bytes, used to read the right number of bytes after the header. */
    private int keySize;

    /** The length of the value in bytes, used to read the right number of bytes after the key. */
    private int valueSize;

    /**
     * Creates an empty header with all fields at their default values.
     * Mainly used internally before populating fields via {@link #decodeHeader(byte[])}.
     */
    public Header() {
    }

    /**
     * Creates a fully populated header with the given values.
     *
     * @param checkSum  the checksum of the record for integrity verification
     * @param tombstone {@code 1} if this record is deleted, {@code 0} otherwise
     * @param timeStamp the time the record was written, as a Unix timestamp in seconds
     * @param keySize   the length of the key in bytes
     * @param valueSize the length of the value in bytes
     */
    public Header(int checkSum, byte tombstone, long timeStamp, int keySize, int valueSize) {
        this.checkSum = checkSum;
        this.tombstone = tombstone;
        this.timeStamp = timeStamp;
        this.keySize = keySize;
        this.valueSize = valueSize;
    }

    /**
     * Deserializes a {@code Header} from a raw byte array read off disk.
     *
     * <p>This is the preferred way to reconstruct a header during a read operation.
     * It validates the buffer size upfront and gives you a fully initialized object
     * in one step, rather than constructing and decoding separately.
     *
     * @param buf the byte array to read from, must be at least {@value #HEADER_SIZE} bytes
     * @return a fully populated {@code Header} instance
     * @throws Exception if the buffer is too small to contain a valid header
     */
    public static Header fromBytes(byte[] buf) throws Exception {
        if (buf.length < HEADER_SIZE) {
            throw new Exception("Buffer too small for header");
        }

        Header header = new Header();
        header.decodeHeader(buf);
        return header;
    }

    /**
     * Serializes this header into a {@value #HEADER_SIZE}-byte array, ready to be
     * written to disk.
     *
     * <p>Fields are written in little-endian order in this sequence:
     * checksum, tombstone, timestamp, key size, value size.
     *
     * @return a byte array representing this header on disk
     */
    public byte[] encodeHeader() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(checkSum);
        buffer.put(tombstone);
        buffer.putLong(timeStamp);
        buffer.putInt(keySize);
        buffer.putInt(valueSize);

        return buffer.array();
    }

    /**
     * Populates this header's fields by reading from the given byte array.
     *
     * <p>This is the inverse of {@link #encodeHeader()}. It expects the bytes to be
     * in little-endian order, matching the format written by {@code encodeHeader}.
     * If you just need a new {@code Header} from bytes, prefer {@link #fromBytes(byte[])}
     * which does this for you and also validates the buffer size.
     *
     * @param buf the byte array to decode from, must be at least {@value #HEADER_SIZE} bytes
     * @throws Exception if the buffer is too small to decode a valid header
     */
    public void decodeHeader(byte[] buf) throws Exception {
        if (buf.length < HEADER_SIZE) {
            throw new Exception("Buffer too small for header decoding");
        }

        ByteBuffer buffer = ByteBuffer.wrap(buf, 0, HEADER_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        this.checkSum = buffer.getInt();
        this.tombstone = buffer.get();
        this.timeStamp = buffer.getLong();
        this.keySize = buffer.getInt();
        this.valueSize = buffer.getInt();
    }

    /**
     * Marks this record as deleted by setting the tombstone flag to {@code 1}.
     *
     * <p>In an LSM-tree based store like Gecko, deletes are not done in-place.
     * Instead, a tombstone record is written so that compaction can clean it up later.
     */
    public void markTombstone() {
        this.tombstone = 1;
    }

    /**
     * Returns {@code true} if this record has been marked as deleted.
     *
     * <p>During reads and compaction, tombstoned records should be skipped or
     * removed rather than returned to the caller.
     *
     * @return {@code true} if tombstone is set, {@code false} otherwise
     */
    public boolean isTombstone() {
        return this.tombstone == 1;
    }

    /**
     * Returns the checksum of this record, used to verify data integrity on reads.
     *
     * @return the checksum value
     */
    public int getCheckSum() {
        return checkSum;
    }

    /**
     * Sets the checksum for this record.
     *
     * @param checkSum the checksum value to set
     */
    public void setCheckSum(int checkSum) {
        this.checkSum = checkSum;
    }

    /**
     * Returns the raw tombstone byte ({@code 0} or {@code 1}).
     * For a boolean check, prefer {@link #isTombstone()} instead.
     *
     * @return the tombstone byte
     */
    public byte getTombstone() {
        return tombstone;
    }

    /**
     * Sets the raw tombstone byte directly.
     * To mark a record as deleted, prefer {@link #markTombstone()} instead.
     *
     * @param tombstone the tombstone byte to set
     */
    public void setTombstone(byte tombstone) {
        this.tombstone = tombstone;
    }

    /**
     * Returns the timestamp of when this record was written.
     *
     * @return the timestamp as epoch milliseconds
     */
    public long getTimeStamp() {
        return timeStamp;
    }

    /**
     * Sets the timestamp for this record.
     *
     * @param timeStamp the epoch milliseconds to set
     */
    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }

    /**
     * Returns the length of the key associated with this record, in bytes.
     *
     * @return the key size in bytes
     */
    public int getKeySize() {
        return keySize;
    }

    /**
     * Sets the key size for this record.
     *
     * @param keySize the length of the key in bytes
     */
    public void setKeySize(int keySize) {
        this.keySize = keySize;
    }

    /**
     * Returns the length of the value associated with this record, in bytes.
     *
     * @return the value size in bytes
     */
    public int getValueSize() {
        return valueSize;
    }

    /**
     * Sets the value size for this record.
     *
     * @param valueSize the length of the value in bytes
     */
    public void setValueSize(int valueSize) {
        this.valueSize = valueSize;
    }
}