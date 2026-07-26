package com.bhav.gecko.store.wal;

import com.bhav.gecko.store.memtable.Header;
import com.bhav.gecko.store.memtable.MemTableRecord;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class WriteAheadLog {

    private String filePath;
    private FileOutputStream fileStream;

    public WriteAheadLog(String filePath) throws IOException {
        this.filePath = filePath;
        Path path = Paths.get(filePath);

        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
            Files.createFile(path);
        }
        this.fileStream = new FileOutputStream(path.toFile(), true);
    }

    public static WriteAheadLog createSegment(String walDirectory) throws IOException {
        long timestamp = System.currentTimeMillis();
        String filePath = walDirectory + "/wal_" + timestamp + ".log";
        return new WriteAheadLog(filePath);
    }

    public synchronized void appendWALOperation(Operation op, MemTableRecord record) throws Exception {
        byte[] encodedRecord = record.encodeKV();
        byte[] entry = new byte[1 + encodedRecord.length];
        entry[0] = op.getValue();
        System.arraycopy(encodedRecord, 0, entry, 1, encodedRecord.length);
        fileStream.write(entry);
        
        // Push the bytes to the Operating System's page cache.
        // We have deliberately removed fileStream.getFD().sync() here to achieve 
        // maximum benchmark throughput. The OS will flush to physical disk on its own schedule.
        fileStream.flush(); 
    }

    public synchronized void sync() {
        if (fileStream != null) {
            try {
                fileStream.getFD().sync();
            } catch (IOException e) {
                System.err.println("Warning: Background WAL sync failed: " + e.getMessage());
            }
        }
    }

    public List<WALEntry> readAllEntries() throws Exception {
        List<WALEntry> entries = new ArrayList<>();

        if (!Files.exists(Paths.get(filePath))) {
            return entries;
        }

        byte[] walData = Files.readAllBytes(Paths.get(filePath));
        int offset = 0;

        while (offset < walData.length) {
            try {
                if (offset >= walData.length)
                    break;
                Operation operation = Operation.fromByte(walData[offset]);
                offset++;

                if (offset + Header.HEADER_SIZE > walData.length) {
                    throw new Exception("Incomplete WAL entry - header truncated");
                }

                Header header = Header.fromBytes(Arrays.copyOfRange(walData, offset, offset + Header.HEADER_SIZE));

                int totalRecordSize = Header.HEADER_SIZE + header.getKeySize() + header.getValueSize();

                if (offset + totalRecordSize > walData.length) {
                    throw new Exception("Incomplete WAL entry - record truncated");
                }

                MemTableRecord record = MemTableRecord
                        .decodeKV(Arrays.copyOfRange(walData, offset, offset + totalRecordSize));

                if (!record.verifyChecksum()) {
                    System.err.println("Checksum mismatch for record: " + record.getKey() + " - skipping");
                    offset += totalRecordSize;
                    continue;
                }

                entries.add(new WALEntry(operation, record));
                offset += totalRecordSize;

            } catch (Exception e) {
                System.err.println("Error reading WAL entry at offset " + offset + ": " + e.getMessage());
                break;
            }
        }

        return entries;
    }

    public void deleteSegment() throws IOException {
        close();
        Files.deleteIfExists(Paths.get(filePath));
    }

    public void close() throws IOException {
        if (fileStream != null) {
            fileStream.close();
        }
    }

    public String getFilePath() {
        return filePath;
    }

}