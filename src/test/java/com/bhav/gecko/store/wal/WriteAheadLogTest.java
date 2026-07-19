package com.bhav.gecko.store.wal;

import com.bhav.gecko.store.memtable.Header;
import com.bhav.gecko.store.memtable.MemTableRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class WriteAheadLogTest {

    @TempDir
    Path tempDir;

    @Test
    void testAppendAndReadSingleEntry() throws Exception {
        String walPath = tempDir.resolve("wal_single.log").toString();
        WriteAheadLog wal = new WriteAheadLog(walPath);

        MemTableRecord record = new MemTableRecord("key1", "value1");
        wal.appendWALOperation(Operation.PUT, record);
        wal.close();

        WriteAheadLog readWal = new WriteAheadLog(walPath);
        List<WALEntry> entries = readWal.readAllEntries();
        readWal.close();

        assertEquals(1, entries.size());
        WALEntry entry = entries.get(0);
        assertEquals(Operation.PUT, entry.getOperation());
        assertEquals("key1", entry.getRecord().getKey());
        assertEquals("value1", entry.getRecord().getValue());
        
        System.out.println("✅ Passed Scenario: Standard Operation - Append and Read Single Entry");
    }

    @Test
    void testMultipleEntriesWithPutAndDelete() throws Exception {
        String walPath = tempDir.resolve("wal_multiple.log").toString();
        WriteAheadLog wal = new WriteAheadLog(walPath);

        MemTableRecord record1 = new MemTableRecord("key1", "value1");
        MemTableRecord record2 = new MemTableRecord("key2", "value2");
        MemTableRecord tombstone = new MemTableRecord("key1", "value1", (int) (System.currentTimeMillis() / 1000), true);

        wal.appendWALOperation(Operation.PUT, record1);
        wal.appendWALOperation(Operation.PUT, record2);
        wal.appendWALOperation(Operation.DELETE, tombstone);
        wal.close();

        WriteAheadLog readWal = new WriteAheadLog(walPath);
        List<WALEntry> entries = readWal.readAllEntries();
        readWal.close();

        assertEquals(3, entries.size());

        assertEquals(Operation.PUT, entries.get(0).getOperation());
        assertEquals("key1", entries.get(0).getRecord().getKey());

        assertEquals(Operation.PUT, entries.get(1).getOperation());
        assertEquals("key2", entries.get(1).getRecord().getKey());

        assertEquals(Operation.DELETE, entries.get(2).getOperation());
        assertEquals("key1", entries.get(2).getRecord().getKey());
        assertTrue(entries.get(2).getRecord().isDeleted());
        
        System.out.println("✅ Passed Scenario: Standard Operation - Multiple Entries with PUT and DELETE");
    }

    @Test
    void testSegmentCreationAndNaming() throws Exception {
        WriteAheadLog wal = WriteAheadLog.createSegment(tempDir.toString());
        wal.close(); // Close to release file lock

        Path walPath = Path.of(wal.getFilePath());
        assertTrue(Files.exists(walPath));
        assertTrue(walPath.getFileName().toString().startsWith("wal_"));
        assertTrue(walPath.getFileName().toString().endsWith(".log"));
        
        System.out.println("✅ Passed Scenario: Segment Lifecycle - Creation and Naming correctly formats wal_<timestamp>.log");
    }

    @Test
    void testDeleteSegment() throws Exception {
        String walPath = tempDir.resolve("wal_to_delete.log").toString();
        WriteAheadLog wal = new WriteAheadLog(walPath);
        
        MemTableRecord record = new MemTableRecord("key1", "value1");
        wal.appendWALOperation(Operation.PUT, record);
        
        assertTrue(Files.exists(Path.of(walPath)));
        
        wal.deleteSegment();
        
        assertFalse(Files.exists(Path.of(walPath)));
        
        System.out.println("✅ Passed Scenario: Segment Lifecycle - Delete Segment successfully removes the file");
    }

    @Test
    void testIncompleteWALEntry_HeaderTruncated() throws Exception {
        String walPath = tempDir.resolve("wal_header_trunc.log").toString();
        WriteAheadLog wal = new WriteAheadLog(walPath);

        MemTableRecord record1 = new MemTableRecord("key1", "value1");
        wal.appendWALOperation(Operation.PUT, record1);
        wal.close();

        // Append just a few bytes, simulating a cut off during header write
        Files.write(Path.of(walPath), new byte[] { Operation.PUT.getValue(), 0, 1, 2 }, StandardOpenOption.APPEND);

        WriteAheadLog readWal = new WriteAheadLog(walPath);
        List<WALEntry> entries = readWal.readAllEntries();
        readWal.close();

        // Should recover the first valid entry and ignore the truncated one
        assertEquals(1, entries.size());
        assertEquals("key1", entries.get(0).getRecord().getKey());
        
        System.out.println("✅ Passed Scenario: Crash Safety - Incomplete WAL Entry (Header Truncated) gracefully handled");
    }

    @Test
    void testIncompleteWALEntry_RecordTruncated() throws Exception {
        String walPath = tempDir.resolve("wal_record_trunc.log").toString();
        WriteAheadLog wal = new WriteAheadLog(walPath);

        MemTableRecord record1 = new MemTableRecord("key1", "value1");
        wal.appendWALOperation(Operation.PUT, record1);
        
        // Let's create a second record, but we will intercept its bytes to truncate them
        MemTableRecord record2 = new MemTableRecord("key2", "value2");
        byte[] encodedRecord2 = record2.encodeKV();
        
        wal.close(); // close so we can manually append the incomplete record

        // Append Operation byte
        Files.write(Path.of(walPath), new byte[] { Operation.PUT.getValue() }, StandardOpenOption.APPEND);
        
        // Append Header only (17 bytes)
        byte[] headerBytes = new byte[Header.HEADER_SIZE];
        System.arraycopy(encodedRecord2, 0, headerBytes, 0, Header.HEADER_SIZE);
        Files.write(Path.of(walPath), headerBytes, StandardOpenOption.APPEND);

        // Append just a part of the payload, simulating truncation
        byte[] truncatedPayload = new byte[2]; // not enough to satisfy the header's length claims
        Files.write(Path.of(walPath), truncatedPayload, StandardOpenOption.APPEND);

        WriteAheadLog readWal = new WriteAheadLog(walPath);
        List<WALEntry> entries = readWal.readAllEntries();
        readWal.close();

        // Should recover the first valid entry and ignore the truncated record
        assertEquals(1, entries.size());
        assertEquals("key1", entries.get(0).getRecord().getKey());
        
        System.out.println("✅ Passed Scenario: Crash Safety - Incomplete WAL Entry (Record Truncated) gracefully handled");
    }

    @Test
    void testChecksumMismatch() throws Exception {
        String walPath = tempDir.resolve("wal_corrupted.log").toString();
        WriteAheadLog wal = new WriteAheadLog(walPath);

        MemTableRecord record1 = new MemTableRecord("key1", "value1");
        MemTableRecord record2 = new MemTableRecord("key2", "value2"); // This will be corrupted
        MemTableRecord record3 = new MemTableRecord("key3", "value3");

        wal.appendWALOperation(Operation.PUT, record1);
        wal.appendWALOperation(Operation.PUT, record2);
        wal.appendWALOperation(Operation.PUT, record3);
        wal.close();

        // Corrupt the file. We'll find the second record and flip a byte.
        byte[] fileBytes = Files.readAllBytes(Path.of(walPath));
        
        // First record size = 1 (op) + 17 (header) + len(key1) + len(value1) 
        // key1 = 4, value1 = 6, total record size = 17+4+6 = 27, total entry = 28
        // Second record starts at offset 28.
        // Second record payload starts at 28 + 1 (op) + 17 (header) = 46.
        // Let's flip the byte at offset 47 (part of key2 or value2).
        
        int offsetToCorrupt = 1 + Header.HEADER_SIZE + record1.getKey().length() + record1.getValue().length();
        offsetToCorrupt += 1 + Header.HEADER_SIZE + 2; // Move into the second record's payload
        
        // Sanity check to avoid array out of bounds just in case lengths vary due to internal implementation details
        if (offsetToCorrupt < fileBytes.length) {
            fileBytes[offsetToCorrupt] = (byte) (fileBytes[offsetToCorrupt] ^ 0xFF); // Flip all bits of this byte
            Files.write(Path.of(walPath), fileBytes, StandardOpenOption.TRUNCATE_EXISTING);
        }

        WriteAheadLog readWal = new WriteAheadLog(walPath);
        List<WALEntry> entries = readWal.readAllEntries();
        readWal.close();

        // We expect record1 to be read successfully.
        // record2 will fail the checksum validation, so it's skipped.
        // record3 should be read successfully after record2 is skipped.
        assertEquals(2, entries.size());
        assertEquals("key1", entries.get(0).getRecord().getKey());
        assertEquals("key3", entries.get(1).getRecord().getKey());
        
        System.out.println("✅ Passed Scenario: Data Corruption - Checksum Mismatch caught and corrupted record safely skipped");
    }
}
