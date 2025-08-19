package com.bhav.gecko.store.wal;

import com.bhav.gecko.store.memtable.Header;
import com.bhav.gecko.store.memtable.Record;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class WriteAheadLog {
    private static final int WAL_BATCH_THRESHOLD = 1024 * 1024 * 3; // 3MB

    private String filePath;
    private List<Byte> opsBatch;
    private int size;

    public WriteAheadLog(String walDirectory) throws IOException {
        this.filePath = walDirectory + "/wal.log";
        this.opsBatch = new ArrayList<>();
        this.size = 0;

        Files.createDirectories(Paths.get(walDirectory));
        if (!Files.exists(Paths.get(filePath))) {
            Files.createFile(Paths.get(filePath));
        }
    }

    public void appendWALOperation(Operation op, Record record) throws Exception {
        List<Byte> buffer = new ArrayList<>();

        buffer.add(op.getValue());

        byte[] encodedRecord = record.encodeKV();
        for (byte b : encodedRecord) {
            buffer.add(b);
        }

        opsBatch.addAll(buffer);
        size += buffer.size();

        if (size >= WAL_BATCH_THRESHOLD) {
            flushToDisk();
        }
    }

    private void flushToDisk() throws IOException {
        if (opsBatch.isEmpty())
            return;

        byte[] data = new byte[opsBatch.size()];
        for (int i = 0; i < opsBatch.size(); i++) {
            data[i] = opsBatch.get(i);
        }

        Files.write(Paths.get(filePath), data, StandardOpenOption.APPEND);

        clearBatch();
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

                Record record = Record.decodeKV(Arrays.copyOfRange(walData, offset, offset + totalRecordSize));

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

    public void truncateWAL() throws IOException {
        // Clear the WAL file after successful recovery
        Files.write(Paths.get(filePath), new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
        clearBatch();
    }

    public boolean hasRecoveryData() {
        try {
            return Files.exists(Paths.get(filePath)) && Files.size(Paths.get(filePath)) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private void clearBatch() {
        opsBatch.clear();
        size = 0;
    }

    public void close() throws IOException {
        flushToDisk();
    }
}