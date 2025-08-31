package com.bhav.gecko.service;

import java.util.Map;
import java.util.Set;

import com.bhav.gecko.dto.MemtableStats;
import com.bhav.gecko.exception.KeyNotFoundException;
import com.bhav.gecko.store.memtable.Record;

/**
 * Service interface for disk-based storage operations.
 * Provides methods for CRUD operations, bulk operations, and storage
 * management.
 */
public interface DiskStoreService {

    /**
     * Retrieves all key-value pairs from the memtable.
     *
     * @return Map containing all key-value pairs as Records
     */
    Map<String, Record> getAllKVPairs();

    /**
     * Retrieves all keys from the memtable.
     *
     * @return Set containing all keys
     */
    Set<String> getAllKeys();

    /**
     * Gets current memtable statistics.
     *
     * @return MemtableStats object containing size and other metrics
     */
    MemtableStats getMemtableStats();

    /**
     * Stores a key-value pair in the memtable and writes to WAL.
     *
     * @param key   the key to store
     * @param value the value to store
     * @throws Exception if storage operation fails
     */
    void put(String key, String value) throws Exception;

    /**
     * Performs bulk insertion of multiple key-value pairs.
     *
     * @param data Map containing key-value pairs to insert
     * @throws Exception if any insertion fails
     */
    void bulkInsert(Map<String, String> data) throws Exception;

    /**
     * Retrieves a record by its key.
     *
     * @param key the key to retrieve
     * @return Record associated with the key
     * @throws KeyNotFoundException if key is not found
     */
    Record get(String key) throws KeyNotFoundException;

    /**
     * Deletes a record by marking it with a tombstone.
     *
     * @param key the key to delete
     * @throws Exception if deletion fails or key not found
     */
    void delete(String key) throws Exception;

    /**
     * Recovers data from Write-Ahead Log on startup.
     *
     * @throws Exception if recovery fails
     */
    void recoverFromWAL() throws Exception;

    // TODO:
    // /**
    // * Flushes memtable data to disk storage (SSTable).
    // *
    // * @throws Exception if flush operation fails
    // */
    // void flush() throws Exception;

    // TODO:
    // /**
    // * Checks if memtable size exceeds threshold and flushes if necessary.
    // *
    // * @throws Exception if flush operation fails
    // */
    // void checkAndFlush() throws Exception;
}