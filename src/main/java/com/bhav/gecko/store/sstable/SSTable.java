package com.bhav.gecko.store.sstable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.bhav.gecko.store.memtable.Header;
import com.bhav.gecko.store.memtable.MemTableRecord;

public class SSTable {

    private static final String DATA_FILE_EXTENSION = ".data";
    private static final String INDEX_FILE_EXTENSION = ".index";
    private static final String BLOOM_FILE_EXTENSION = ".bloom";
    private static final int SPARSE_INDEX_SAMPLE_SIZE = 4;
    private static final AtomicInteger sstTableCounter = new AtomicInteger(0);

    private RandomAccessFile dataFile;
    private RandomAccessFile indexFile;
    private BloomFilterStore bloomFilter;
    private final int sstCounter;
    private String minKey;
    private String maxKey;
    private Integer sizeInBytes = 0;
    private List<SparseIndex> sparseKeys = new ArrayList<>();

    public SSTable(int sstCounter) {
        this.sstCounter = sstCounter;
    }

    /**
     * Advances the global SSTable counter to at least the given value.
     * Called during startup so new flushes don't produce filenames that
     * already exist on disk from a previous run.
     */
    public static void syncCounter(int minValue) {
        int current = sstTableCounter.get();
        while (current < minValue) {
            if (sstTableCounter.compareAndSet(current, minValue))
                break;
            current = sstTableCounter.get();
        }
    }

    public static SSTable initSSTableOnDisk(List<MemTableRecord> entries, String SST_DIR) throws IOException {
        int counter = sstTableCounter.incrementAndGet();
        SSTable table = new SSTable(counter);
        table.initTableFiles(SST_DIR);
        writeEntriesToSST(entries, table);
        return table;
    }

    public static SSTable loadFromDisk(String sstDir, int sstCounter) throws IOException {
        SSTable table = new SSTable(sstCounter);
        table.initTableFiles(sstDir);
        table.sparseKeys = loadSparseIndexFile(table.indexFile);
        table.bloomFilter.loadFromFile(0);
        return table;
    }

    public MemTableRecord search(String key) throws Exception {
        // 1. Bloom Filter Check
        if (!bloomFilter.mightContain(key)) {
            return null;
        }

        // 2. Sparse Index Binary Search
        if (sparseKeys.isEmpty()) {
            return null;
        }

        int left = 0;
        int right = sparseKeys.size() - 1;
        int bestIdx = 0;

        while (left <= right) {
            int mid = left + (right - left) / 2;
            int cmp = sparseKeys.get(mid).getKey().compareTo(key);

            if (cmp == 0) {
                bestIdx = mid;
                break;
            } else if (cmp < 0) {
                bestIdx = mid; // Candidate (largest key <= target)
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }

        long startOffset = sparseKeys.get(bestIdx).getOffsetInbytes();
        long endOffset = (bestIdx + 1 < sparseKeys.size())
                ? sparseKeys.get(bestIdx + 1).getOffsetInbytes()
                : dataFile.length();

        // 3. Sequential Disk Scan
        dataFile.seek(startOffset);

        while (dataFile.getFilePointer() < endOffset) {
            byte[] headerBytes = new byte[Header.HEADER_SIZE];
            if (dataFile.read(headerBytes) < headerBytes.length) {
                break;
            }

            Header header = new Header();
            header.decodeHeader(headerBytes);

            int keySize = header.getKeySize();
            int valueSize = header.getValueSize();

            int totalSize = Header.HEADER_SIZE + keySize + valueSize;
            byte[] recordBytes = new byte[totalSize];
            System.arraycopy(headerBytes, 0, recordBytes, 0, headerBytes.length);

            dataFile.readFully(recordBytes, headerBytes.length, keySize + valueSize);

            MemTableRecord record = MemTableRecord.decodeKV(recordBytes);

            int cmp = record.getKey().compareTo(key);
            if (cmp == 0) {
                if (!record.verifyChecksum()) {
                    System.err.println("Data corruption: Checksum mismatch for key '" + key + "' in sst_" + sstCounter);
                    return null; // Treat corrupted record as missing
                }
                return record;
            } else if (cmp > 0) {
                // Since the SSTable is sorted, if we encounter a key greater than our target,
                // it's not here.
                return null;
            }
        }

        return null;
    }

    public void close() throws IOException {
        if (dataFile != null) {
            dataFile.close();
        }
        if (indexFile != null) {
            indexFile.close();
        }
    }

    public void initTableFiles(String directory) throws IOException {
        File storageDir = new File(directory);
        if (!storageDir.exists()) {
            if (!storageDir.mkdirs()) {
                throw new IOException("Failed to create storage directory: " + directory);
            }
        }

        storageDir.setReadable(true, false);
        storageDir.setWritable(true, true);
        storageDir.setExecutable(true, true);

        String baseFilename = getNextSstFilename(directory, sstCounter);
        File dataFile = new File(baseFilename + DATA_FILE_EXTENSION);
        this.dataFile = new RandomAccessFile(dataFile, "rw");

        File indexFile = new File(baseFilename + INDEX_FILE_EXTENSION);
        this.indexFile = new RandomAccessFile(indexFile, "rw");

        File bloomFile = new File(baseFilename + BLOOM_FILE_EXTENSION);
        this.bloomFilter = new BloomFilterStore(bloomFile);
    }

    private String getNextSstFilename(String directory, int sstCounter) {
        return directory + File.separator + "sst_" + sstCounter;
    }

    private static void writeEntriesToSST(List<MemTableRecord> sortedEntries, SSTable table)
            throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int byteOffsetCounter = 0;

        table.minKey = sortedEntries.getFirst().getKey();
        table.maxKey = sortedEntries.getLast().getKey();

        // Every 1000th key will be put into the sparse index
        for (int i = 0; i < sortedEntries.size(); i++) {
            MemTableRecord record = sortedEntries.get(i);
            table.sizeInBytes += record.getRecordSize();

            if (i % SPARSE_INDEX_SAMPLE_SIZE == 0) {
                table.sparseKeys.add(new SparseIndex(
                        record.getHeader().getKeySize(),
                        record.getKey(),
                        byteOffsetCounter));
            }

            byteOffsetCounter += record.getRecordSize();
            byte[] encodedRecord = record.encodeKV();
            buf.write(encodedRecord);
        }

        // Write encoded entries to the SSTable data file
        table.dataFile.write(buf.toByteArray());
        // Set up sparse index
        populateSparseIndexFile(table.sparseKeys, table.indexFile);

        // Set up + populate bloom filter
        table.bloomFilter.initBloomFilterAttrs(sortedEntries.size());
        populateBloomFilter(sortedEntries, table.bloomFilter);
    }

    private static void populateSparseIndexFile(List<SparseIndex> sparseKeys, RandomAccessFile indexFile)
            throws IOException {
        for (SparseIndex entry : sparseKeys) {
            byte[] keyBytes = entry.getKey().getBytes();
            int keySize = keyBytes.length;
            int offset = entry.getOffsetInbytes();
            // 4 bytes keySize + variable key bytes + 4 bytes offset
            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + keySize + Integer.BYTES);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(keySize);
            buffer.put(keyBytes);
            buffer.putInt(offset);
            indexFile.write(buffer.array());
        }
    }

    private static void populateBloomFilter(List<MemTableRecord> sortedEntries, BloomFilterStore bloomFilter)
            throws IOException {
        for (MemTableRecord record : sortedEntries) {
            bloomFilter.add(record.getKey());
        }

        bloomFilter.saveToFile();
    }

    private static List<SparseIndex> loadSparseIndexFile(RandomAccessFile indexFile) throws IOException {
        List<SparseIndex> sparseKeys = new ArrayList<>();
        byte[] intBuf = new byte[Integer.BYTES];

        while (indexFile.read(intBuf) == Integer.BYTES) {
            // Read keySize
            int keySize = ByteBuffer.wrap(intBuf)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getInt();

            // Read key
            byte[] keyBytes = new byte[keySize];
            indexFile.readFully(keyBytes);
            String key = new String(keyBytes);

            // Read offset
            indexFile.readFully(intBuf);
            int offset = ByteBuffer.wrap(intBuf)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getInt();

            sparseKeys.add(new SparseIndex(keySize, key, offset));
        }

        return sparseKeys;
    }

    private static void loadBloomFilter(BloomFilterStore bloomFilter,
            int numElements) throws IOException {
        bloomFilter.loadFromFile(numElements);
    }

    public RandomAccessFile getDataFile() {
        return dataFile;
    }

    public void setDataFile(RandomAccessFile dataFile) {
        this.dataFile = dataFile;
    }

    public RandomAccessFile getIndexFile() {
        return indexFile;
    }

    public void setIndexFile(RandomAccessFile indexFile) {
        this.indexFile = indexFile;
    }

    public BloomFilterStore getBloomFilter() {
        return bloomFilter;
    }

    public void setBloomFilter(BloomFilterStore bloomFilter) {
        this.bloomFilter = bloomFilter;
    }

    public Integer getSstCounter() {
        return sstCounter;
    }

    public String getMinKey() {
        return minKey;
    }

    public void setMinKey(String minKey) {
        this.minKey = minKey;
    }

    public String getMaxKey() {
        return maxKey;
    }

    public void setMaxKey(String maxKey) {
        this.maxKey = maxKey;
    }

    public Integer getSizeInBytes() {
        return sizeInBytes;
    }

    public void setSizeInBytes(Integer sizeInBytes) {
        this.sizeInBytes = sizeInBytes;
    }

    /**
     * Closes all open file handles and deletes the three files that make up this
     * SSTable (.data, .index, .bloom) from disk.
     *
     * Must be called only after the SSTable has been removed from the active list
     * so no reader can attempt to use it while files are being deleted.
     * On Windows, the close() must happen before any delete attempt because the
     * OS holds a file lock while a RandomAccessFile is open.
     */
    public void deleteFiles(String sstDir) throws IOException {
        close(); // release OS file locks first
        String base = sstDir + File.separator + "sst_" + sstCounter;
        deleteIfExists(base + DATA_FILE_EXTENSION);
        deleteIfExists(base + INDEX_FILE_EXTENSION);
        deleteIfExists(base + BLOOM_FILE_EXTENSION);
    }

    private void deleteIfExists(String path) {
        File f = new File(path);
        if (f.exists() && !f.delete()) {
            // Log rather than throw — a failed delete is not worth bringing down the server.
            // The file is simply orphaned on disk and can be cleaned up on the next restart.
            System.err.println("Warning: could not delete SSTable file: " + path);
        }
    }

}
