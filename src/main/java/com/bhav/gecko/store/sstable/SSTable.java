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

import com.bhav.gecko.store.memtable.MemTableRecord;
import org.springframework.beans.factory.annotation.Value;

public class SSTable {

    private static final String DATA_FILE_EXTENSION = ".data";
    private static final String INDEX_FILE_EXTENSION = ".index";
    private static final String BLOOM_FILE_EXTENSION = ".bloom";
    private static final int SPARSE_INDEX_SAMPLE_SIZE = 1000;
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

    public static SSTable initSSTableOnDisk(List<MemTableRecord> entries, String SST_DIR) throws IOException {
        int counter = sstTableCounter.incrementAndGet();
        SSTable table = new SSTable(counter);
        table.initTableFiles(SST_DIR);
        writeEntriesToSST(entries, table);
        return table;
    }

    public static SSTable loadFromDisk(String sstDir, long sstId) {
        return null;
    }

    public void initTableFiles(String directory) throws IOException {
        File storageDir = new File("./storage");
        if (!storageDir.exists()) {
            if (!storageDir.mkdirs()) {
                throw new IOException("Failed to create storage directory");
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
        return String.format("../%s/sst_%d", directory, sstCounter);
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

    private static void populateSparseIndexFile(List<SparseIndex> sparseKeys, RandomAccessFile indexFile) throws  IOException {
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

    private static void populateBloomFilter(List<MemTableRecord> sortedEntries, BloomFilterStore bloomFilter) throws IOException {
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

    // public SparseIndex[] getSparseKeys() {
    // return sparseKeys;
    // }

    // public void setSparseKeys(SparseIndex[] sparseKeys) {
    // this.sparseKeys = sparseKeys;
    // }

}
