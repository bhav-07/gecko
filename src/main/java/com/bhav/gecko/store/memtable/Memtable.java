package com.bhav.gecko.store.memtable;

import java.util.*;

import org.springframework.stereotype.Service;

import com.bhav.gecko.dto.MemtableStats;
import com.bhav.gecko.exception.KeyNotFoundException;

@Service
public class Memtable {
    private TreeMap<String, Record> data;
    private int sizeInBytes;

    public Memtable() {
        this.data = new TreeMap<>();
        this.sizeInBytes = 0;
    }

    public void put(String key, Record value) {
        Record existingRecord = data.get(key);
        if (existingRecord != null) {
            sizeInBytes -= existingRecord.getRecordSize();
        }
        data.put(key, value);
        sizeInBytes += value.getRecordSize();
    }

    public MemtableStats getStats() {
        return new MemtableStats(
                size(),
                getSizeInBytes(),
                isEmpty());
    }

    public Record get(String key) throws KeyNotFoundException {
        Record record = data.get(key);
        if (record == null) {
            throw new KeyNotFoundException("Key not found: " + key);
        }
        return record;
    }

    public void delete(String key, int recordSize) throws Exception {
        data.remove(key);
        sizeInBytes -= recordSize;
    }

    public Map<String, Record> getAllKVPairs() {
        return new TreeMap<>(data);
    }

    public void clear() {
        data.clear();
        sizeInBytes = 0;
    }

    public int getSizeInBytes() {
        return sizeInBytes;
    }

    public int size() {
        return data.size();
    }

    public boolean isEmpty() {
        return data.isEmpty();
    }

    public Set<String> getKeys() {
        return data.keySet();
    }

    public boolean containsKey(String key) {
        return data.containsKey(key);
    }

    public void setSizeInBytes(int sizeInBytes) {
        this.sizeInBytes = sizeInBytes;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Memtable {\n");
        sb.append("  size        = ").append(size()).append("\n");
        sb.append("  sizeInBytes = ").append(sizeInBytes).append("\n");
        sb.append("  isEmpty     = ").append(isEmpty()).append("\n");

        if (!data.isEmpty()) {
            sb.append("  entries:\n");
            int previewLimit = 5;
            int count = 0;
            for (Map.Entry<String, Record> entry : data.entrySet()) {
                sb.append("    ")
                        .append(entry.getKey())
                        .append(" => ")
                        .append(entry.getValue())
                        .append("\n");
                if (++count >= previewLimit) {
                    sb.append("    ... (").append(size() - previewLimit).append(" more entries)\n");
                    break;
                }
            }
        }

        sb.append("}");
        return sb.toString();
    }

}