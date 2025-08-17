package com.bhav.gecko.service;

import org.springframework.stereotype.Service;

import com.bhav.gecko.dto.MemtableStats;
import com.bhav.gecko.store.KeyNotFoundException;
import com.bhav.gecko.store.Memtable;
import com.bhav.gecko.store.Record;

import java.util.Map;
import java.util.Set;

@Service
public class MemtableService {
    private final Memtable memtable;

    public MemtableService() {
        this.memtable = new Memtable();
    }

    public void put(String key, String value) {
        Record record = new Record(key, value);
        memtable.put(key, record);
    }

    public Record get(String key) throws KeyNotFoundException {
        return memtable.get(key);
    }

    public Map<String, Record> getAllKVPairs() {
        return memtable.getAllKVPairs();
    }

    public Set<String> getAllKeys() {
        return memtable.getKeys();
    }

    public MemtableStats getStats() {
        return new MemtableStats(
                memtable.size(),
                memtable.getSizeInBytes(),
                memtable.isEmpty());
    }

    public void clear() {
        try {
            java.lang.reflect.Method clearMethod = Memtable.class.getDeclaredMethod("clear");
            clearMethod.setAccessible(true);
            clearMethod.invoke(memtable);
        } catch (Exception e) {
            throw new RuntimeException("Failed to clear memtable", e);
        }
    }

    public void bulkInsert(Map<String, String> data) {
        data.forEach(this::put);
    }

}