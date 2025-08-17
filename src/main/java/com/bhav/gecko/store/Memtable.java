package com.bhav.gecko.store;

import java.util.*;
import java.util.stream.Collectors;

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

    public Record get(String key) throws KeyNotFoundException {
        Record record = data.get(key);
        if (record == null) {
            throw new KeyNotFoundException("Key not found: " + key);
        }
        return record;
    }

    public Map<String, Record> getAllKVPairs() {
        return new HashMap<>(data);
    }

    public void printAllRecords() {
        System.out.println(returnAllRecordsInSortedOrder());
    }

    private List<Record> returnAllRecordsInSortedOrder() {
        return data.values()
                .stream()
                .collect(Collectors.toList());
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
}