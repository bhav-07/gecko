package com.bhav.gecko.store.sstable;

import com.bhav.gecko.store.memtable.MemTableRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the SSTable fixes for orphaned files (C2), missing files (C3) and
 * corrupted records (C4) from the ACID review.
 */
class SSTableDurabilityTest {

    @TempDir
    Path tempDir;

    private static List<MemTableRecord> records(String... kv) {
        List<MemTableRecord> list = new ArrayList<>();
        for (int i = 0; i < kv.length; i += 2) {
            list.add(new MemTableRecord(kv[i], kv[i + 1]));
        }
        return list;
    }

    @Test
    void newSSTableTruncatesOrphanFilesWithTheSameId() throws Exception {
        String dir = tempDir.toString();
        // Find out which id the next table will get, then plant larger orphan files
        // under that id, as a crash between file creation and the manifest write would.
        SSTable probe = SSTable.initSSTableOnDisk(records("a", "1"), dir);
        probe.close();
        int nextId = probe.getSstCounter() + 1;
        byte[] junk = new byte[64 * 1024];
        java.util.Arrays.fill(junk, (byte) 0x5A);
        Files.write(tempDir.resolve("sst_" + nextId + ".data"), junk);
        Files.write(tempDir.resolve("sst_" + nextId + ".index"), junk);

        List<MemTableRecord> entries = records("apple", "red", "banana", "yellow", "cherry", "dark-red");
        SSTable table = SSTable.initSSTableOnDisk(entries, dir);
        assertEquals(nextId, table.getSstCounter());
        table.close();

        long expected = entries.stream().mapToLong(MemTableRecord::getRecordSize).sum();
        assertEquals(expected, new File(dir, "sst_" + nextId + ".data").length(),
                "data file must not keep the orphan's leftover bytes");

        SSTable loaded = SSTable.loadFromDisk(dir, nextId);
        assertEquals("yellow", loaded.search("banana").getValue());
        assertEquals("dark-red", loaded.search("cherry").getValue());
        assertNull(loaded.search("zzz"));
        loaded.close();
    }

    @Test
    void loadingAMissingSSTableFailsAndCreatesNoFiles() {
        String dir = tempDir.toString();
        IOException e = assertThrows(IOException.class, () -> SSTable.loadFromDisk(dir, 4242));
        assertTrue(e.getMessage().contains("missing or empty"));
        assertFalse(new File(dir, "sst_4242.data").exists(), "load must not create an empty table");
    }

    @Test
    void checksumMismatchOnSearchIsAnErrorNotAMiss() throws Exception {
        String dir = tempDir.toString();
        SSTable table = SSTable.initSSTableOnDisk(records("apple", "red", "banana", "yellow"), dir);
        int id = table.getSstCounter();
        table.close();

        // Flip the last byte of the file, which is inside banana's value.
        try (RandomAccessFile raf = new RandomAccessFile(new File(dir, "sst_" + id + ".data"), "rw")) {
            raf.seek(raf.length() - 1);
            int b = raf.read();
            raf.seek(raf.length() - 1);
            raf.write(b ^ 0xFF);
        }

        SSTable loaded = SSTable.loadFromDisk(dir, id);
        assertEquals("red", loaded.search("apple").getValue());
        assertThrows(IOException.class, () -> loaded.search("banana"));
        loaded.close();
    }

    @Test
    void iteratorThrowsOnCorruptionInsteadOfEndingEarly() throws Exception {
        String dir = tempDir.toString();
        SSTable table = SSTable.initSSTableOnDisk(records("apple", "red", "banana", "yellow"), dir);
        int id = table.getSstCounter();
        table.close();

        try (RandomAccessFile raf = new RandomAccessFile(new File(dir, "sst_" + id + ".data"), "rw")) {
            raf.seek(raf.length() - 1);
            int b = raf.read();
            raf.seek(raf.length() - 1);
            raf.write(b ^ 0xFF);
        }

        SSTable loaded = SSTable.loadFromDisk(dir, id);
        SSTableIterator it = new SSTableIterator(loaded);
        assertEquals("apple", it.next().getKey());
        assertThrows(IOException.class, it::hasNext,
                "compaction must fail rather than commit a merge that silently lost records");
        loaded.close();
    }
}
