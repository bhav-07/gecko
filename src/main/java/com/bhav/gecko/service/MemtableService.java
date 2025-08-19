package com.bhav.gecko.service;

import org.springframework.stereotype.Service;

import com.bhav.gecko.dto.MemtableStats;
import com.bhav.gecko.exception.KeyNotFoundException;
import com.bhav.gecko.store.memtable.Memtable;
import com.bhav.gecko.store.memtable.Record;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Service
public class MemtableService {
    private final Memtable memtable;

    @PostConstruct
    public void initialize() {
        try {
            // Attempt recovery on startup
            memtable.recoverFromWAL();
            System.out.println("Memtable service initialized with WAL recovery");
        } catch (Exception e) {
            System.err.println("WAL recovery failed: " + e.getMessage());
            throw new RuntimeException("Critical: WAL recovery failed", e);
        }
    }

    public MemtableService() {
        try {
            this.memtable = new Memtable("./wal_data");
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize memtable with WAL", e);
        }
    }

    public void put(String key, String value) throws Exception {
        Record record = new Record(key, value);
        memtable.put(key, record);
    }

    public void delete(String key) throws Exception {
        memtable.delete(key);
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

    public void bulkInsert(Map<String, String> data) throws Exception {
        data.forEach((t, u) -> {
            try {
                put(t, u);
            } catch (Exception e) {
                // TODO: Handle this better
                e.printStackTrace();
            }
        });
    }

    @PreDestroy
    public void cleanup() {
        try {
            memtable.closeWAL();
        } catch (IOException e) {
            System.err.println("Error closing WAL: " + e.getMessage());
        }
    }

}