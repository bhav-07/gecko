package com.bhav.gecko.store.memtable;

/**
 * Holds the in-memory index information for a single key stored on disk.
 *
 * <p>Gecko uses a Bitcask-style key directory, which is an in-memory hash map
 * where every key maps to a {@code KeyEntry}. Instead of reading the full record
 * from disk for every lookup, you first check the key directory to find out
 * exactly where on disk the value lives, then go read just that byte range.
 * This makes reads very fast because you never scan the file blindly.
 *
 * <p>A typical lookup works like this:
 * <ol>
 *   <li>Look up the key in the in-memory key directory to get its {@code KeyEntry}.</li>
 *   <li>Seek to {@code valuePosition} in the data file.</li>
 *   <li>Read {@code entrySize} bytes to get the full record.</li>
 *   <li>Use {@code timeStamp} to resolve conflicts if the same key appears in multiple files.</li>
 * </ol>
 */
public class KeyEntry {

    /**
     * The time this record was written, as a Unix timestamp in seconds.
     *
     * <p>During reads or compaction, if the same key shows up in multiple places
     * (for example, across different SSTables), the entry with the highest timestamp
     * is the most recent and should win.
     */
    private int timeStamp;

    /**
     * The byte offset in the data file where this record starts.
     *
     * <p>When you want to read a value, seek to this position in the file
     * and read {@link #entrySize} bytes from there.
     */
    private int valuePosition;

    /**
     * The total size of the full record on disk, in bytes.
     *
     * <p>This covers the header, the key, and the value together. Combined with
     * {@link #valuePosition}, it tells you exactly which byte range to read
     * from the file to get the complete record.
     */
    private int entrySize;

    /**
     * Creates a new {@code KeyEntry} with the given position and size metadata.
     *
     * @param timeStamp     the Unix timestamp of when this record was written
     * @param valuePosition the byte offset in the data file where the record starts
     * @param entrySize     the total size of the record on disk, in bytes
     */
    public KeyEntry(int timeStamp, int valuePosition, int entrySize) {
        this.timeStamp = timeStamp;
        this.valuePosition = valuePosition;
        this.entrySize = entrySize;
    }

    /**
     * Returns the timestamp of when this record was written.
     *
     * @return the Unix timestamp in seconds
     */
    public int getTimeStamp() {
        return timeStamp;
    }

    /**
     * Sets the timestamp for this entry.
     *
     * @param timeStamp the Unix timestamp in seconds to set
     */
    public void setTimeStamp(int timeStamp) {
        this.timeStamp = timeStamp;
    }

    /**
     * Returns the byte offset in the data file where this record starts.
     * Use this to seek to the right position before reading the record.
     *
     * @return the byte offset of the record in the data file
     */
    public int getValuePosition() {
        return valuePosition;
    }

    /**
     * Sets the byte offset in the data file where this record starts.
     *
     * @param valuePosition the byte offset to set
     */
    public void setValuePosition(int valuePosition) {
        this.valuePosition = valuePosition;
    }

    /**
     * Returns the total size of this record on disk, in bytes.
     * This includes the header, key, and value combined.
     *
     * @return the total record size in bytes
     */
    public int getEntrySize() {
        return entrySize;
    }

    /**
     * Sets the total on-disk size of this record.
     *
     * @param entrySize the total size in bytes to set
     */
    public void setEntrySize(int entrySize) {
        this.entrySize = entrySize;
    }
}