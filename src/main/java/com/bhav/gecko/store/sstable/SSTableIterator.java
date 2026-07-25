package com.bhav.gecko.store.sstable;

import com.bhav.gecko.store.memtable.Header;
import com.bhav.gecko.store.memtable.MemTableRecord;

import java.io.IOException;

/**
 * A forward-only, record-by-record iterator over a single SSTable's .data file.
 *
 * Each SSTable's data file is a flat sequence of records written in sorted key
 * order. This iterator reads them one at a time from start to finish, which is
 * the building block the k-way merge in CompactionEngine needs to efficiently
 * merge multiple SSTables without loading them all into memory at once.
 *
 * Usage:
 * SSTableIterator it = new SSTableIterator(sst);
 * while (it.hasNext()) {
 * MemTableRecord record = it.next();
 * // process record
 * }
 * it.close();
 */
public class SSTableIterator {

    private final SSTable sst;
    private MemTableRecord peeked; // one-record lookahead buffer for peek()
    private boolean exhausted = false; // true once we've read past the last record

    public SSTableIterator(SSTable sst) throws IOException {
        this.sst = sst;
        // Seek to the beginning of the data file so we always start from record 0
        sst.getDataFile().seek(0);
    }

    /**
     * Returns true if there is at least one more record to read.
     * Calling this multiple times without calling next() is safe, it does not
     * advance the file pointer.
     */
    public boolean hasNext() throws IOException {
        if (exhausted)
            return false;
        if (peeked != null)
            return true;

        peeked = readNextRecord();
        if (peeked == null) {
            exhausted = true;
            return false;
        }
        return true;
    }

    /**
     * Returns the next record and advances the iterator.
     * Must only be called when hasNext() returns true.
     */
    public MemTableRecord next() throws IOException {
        if (!hasNext()) {
            throw new IllegalStateException("No more records in SSTable sst_" + sst.getSstCounter());
        }
        MemTableRecord result = peeked;
        peeked = null;
        return result;
    }

    /**
     * Returns the next record WITHOUT advancing the iterator.
     * The same record will be returned again by the next call to next() or peek().
     * Used by the PriorityQueue in CompactionEngine to compare records across
     * iterators without consuming them.
     */
    public MemTableRecord peek() throws IOException {
        if (!hasNext())
            return null;
        return peeked;
    }

    /**
     * Returns the SSTable this iterator is reading from.
     * Used by CompactionEngine to identify which SSTable produced each record,
     * so it can resolve duplicates by preferring the higher (newer) SSTable ID.
     */
    public SSTable getSst() {
        return sst;
    }

    /**
     * Reads the next raw record from the data file.
     * Returns null when the file pointer reaches the end of the file.
     * This mirrors the sequential scan logic inside SSTable.search().
     */
    private MemTableRecord readNextRecord() throws IOException {
        var dataFile = sst.getDataFile();

        // Check if we've reached end of file
        if (dataFile.getFilePointer() >= dataFile.length()) {
            return null;
        }

        byte[] headerBytes = new byte[Header.HEADER_SIZE];
        int bytesRead = dataFile.read(headerBytes);
        if (bytesRead < Header.HEADER_SIZE) {
            // Partial header - file is truncated or corrupt. Treat as end of file.
            return null;
        }

        Header header = new Header();
        try {
            header.decodeHeader(headerBytes);
        } catch (Exception e) {
            // Corrupt header,stop iteration
            return null;
        }

        int keySize = header.getKeySize();
        int valueSize = header.getValueSize();
        int totalSize = Header.HEADER_SIZE + keySize + valueSize;

        byte[] recordBytes = new byte[totalSize];
        System.arraycopy(headerBytes, 0, recordBytes, 0, headerBytes.length);
        dataFile.readFully(recordBytes, headerBytes.length, keySize + valueSize);

        try {
            return MemTableRecord.decodeKV(recordBytes);
        } catch (Exception e) {
            // Corrupt record body, stop iteration
            return null;
        }
    }

    /**
     * Resets the iterator back to the beginning of the data file.
     * Not needed during normal compaction but useful for testing.
     */
    public void reset() throws IOException {
        sst.getDataFile().seek(0);
        peeked = null;
        exhausted = false;
    }
}
