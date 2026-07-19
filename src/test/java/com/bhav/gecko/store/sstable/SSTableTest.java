package com.bhav.gecko.store.sstable;

import com.bhav.gecko.store.memtable.MemTableRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SSTableTest {

    @TempDir
    Path tempDir;

    @Test
    void testWriteAndSearchSSTable() throws Exception {
        // 1. Create sorted entries
        List<MemTableRecord> entries = new ArrayList<>();
        entries.add(new MemTableRecord("apple", "red"));
        entries.add(new MemTableRecord("banana", "yellow"));
        entries.add(new MemTableRecord("cherry", "red"));
        entries.add(new MemTableRecord("date", "brown"));
        entries.add(new MemTableRecord("elderberry", "purple"));

        // 2. Initialize SSTable on disk
        String sstDir = tempDir.toString();
        SSTable writtenTable = SSTable.initSSTableOnDisk(entries, sstDir);
        writtenTable.close();
        
        // Ensure files exist
        int sstCounter = writtenTable.getSstCounter();
        File dataFile = new File(sstDir, "sst_" + sstCounter + ".data");
        File indexFile = new File(sstDir, "sst_" + sstCounter + ".index");
        File bloomFile = new File(sstDir, "sst_" + sstCounter + ".bloom");
        
        assertTrue(dataFile.exists());
        assertTrue(indexFile.exists());
        assertTrue(bloomFile.exists());

        // 3. Load SSTable from disk using our new loadFromDisk method
        SSTable loadedTable = SSTable.loadFromDisk(sstDir, sstCounter);
        
        // 4. Verify search functionality
        
        // Existing keys
        MemTableRecord result = loadedTable.search("banana");
        assertNotNull(result);
        assertEquals("banana", result.getKey());
        assertEquals("yellow", result.getValue());

        result = loadedTable.search("elderberry");
        assertNotNull(result);
        assertEquals("elderberry", result.getKey());
        assertEquals("purple", result.getValue());

        // Non-existent keys within range
        result = loadedTable.search("coconut");
        assertNull(result, "Should return null for non-existent key");

        // Non-existent keys outside range (bloom filter should ideally catch these)
        result = loadedTable.search("aardvark");
        assertNull(result, "Should return null for key smaller than minKey");

        result = loadedTable.search("fig");
        assertNull(result, "Should return null for key larger than maxKey");
        
        loadedTable.close();
    }
}
