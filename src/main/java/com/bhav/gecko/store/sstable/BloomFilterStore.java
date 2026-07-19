package com.bhav.gecko.store.sstable;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;

@Component
public class BloomFilterStore {

    private static final Logger logger = LoggerFactory.getLogger(BloomFilterStore.class);
    private static final double FALSE_POSITIVE_PROBABILITY = 0.01;

    private BloomFilter<String> bloomFilter;
    private File bloomFile;

    public BloomFilterStore() {
    }

    public BloomFilterStore(File bloomFile) {
        this.bloomFile = bloomFile;
    }

    public void initBloomFilterAttrs(int numElements) {
        this.bloomFilter = BloomFilter.create(
                Funnels.stringFunnel(Charset.defaultCharset()),
                numElements,
                FALSE_POSITIVE_PROBABILITY);

        logger.info("Bloom filter initialized with capacity: {} and false positive rate: {}",
                numElements, FALSE_POSITIVE_PROBABILITY);
    }

    public void add(String key) {
        if (bloomFilter == null) {
            throw new IllegalStateException("Bloom filter not initialized. Call initBloomFilterAttrs() first.");
        }
        bloomFilter.put(key);
        String source = bloomFile != null ? bloomFile.getName() : "unknown";
        logger.debug("[{}] Added key: {}", source, key);
    }

    public boolean mightContain(String key) {
        if (bloomFilter == null) {
            throw new IllegalStateException("Bloom filter not initialized. Call initBloomFilterAttrs() first.");
        }
        boolean result = bloomFilter.mightContain(key);
        String source = bloomFile != null ? bloomFile.getName() : "unknown";
        logger.debug("[{}] Checking key: {} - Result: {}", source, key, result);
        return result;
    }

    public void saveToFile() throws IOException {
        if (bloomFilter == null || bloomFile == null) {
            throw new IllegalStateException("Bloom filter or file not properly initialized");
        }

        try (FileOutputStream fos = new FileOutputStream(bloomFile)) {
            bloomFilter.writeTo(fos);
            logger.info("Bloom filter saved to file: {}", bloomFile.getAbsolutePath());
        }
    }

    public void loadFromFile(int numElements) throws IOException {
        if (bloomFile == null || !bloomFile.exists()) {
            logger.warn("Bloom filter file not found, creating new filter");
            initBloomFilterAttrs(numElements);
            return;
        }

        try (FileInputStream fis = new FileInputStream(bloomFile)) {
            this.bloomFilter = BloomFilter.readFrom(
                    fis,
                    Funnels.stringFunnel(Charset.defaultCharset()));
            logger.info("Bloom filter loaded from file: {}", bloomFile.getAbsolutePath());
        }
    }

    public void debug() {
        if (bloomFilter == null) {
            logger.info("Bloom filter not initialized");
            return;
        }

        logger.info("=== Bloom Filter Debug Info ===");
        logger.info("Expected false positive probability: {}", bloomFilter.expectedFpp());
        logger.info("Approximate element count: {}", bloomFilter.approximateElementCount());
        logger.info("Bit size: {} bits", getBitSize());
    }

    private long getBitSize() {
        return bloomFilter.approximateElementCount() > 0
                ? (long) (-bloomFilter.approximateElementCount() * Math.log(FALSE_POSITIVE_PROBABILITY) /
                        Math.pow(Math.log(2), 2))
                : 0;
    }

    public void reset(int numElements) {
        initBloomFilterAttrs(numElements);
        logger.info("Bloom filter reset");
    }

    public boolean isInitialized() {
        return bloomFilter != null;
    }

    public void setBloomFile(File bloomFile) {
        this.bloomFile = bloomFile;
    }

    public File getBloomFile() {
        return bloomFile;
    }
}