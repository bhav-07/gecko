package com.bhav.gecko.store.cache;

import com.bhav.gecko.store.memtable.MemTableRecord;

/**
 * Contract for the read cache layer that sits between the memtable and the
 * SSTable disk layer in the read path.
 *
 * Two implementations exist:
 *   - LRUCache: the real LRU-evicting cache used in production
 *   - NoOpCache: a silent no-op used when the cache is disabled via config
 *
 * Callers never need an if (cacheEnabled) check — they just call the interface
 * and the right behavior happens automatically (Null Object Pattern).
 */
public interface ReadCache {

    /**
     * Returns the cached record for the given key, or null on a miss.
     */
    MemTableRecord get(String key);

    /**
     * Inserts or updates an entry in the cache.
     */
    void put(String key, MemTableRecord record);

    /**
     * Removes a key from the cache. Called on every write and delete so stale
     * data never gets served.
     */
    void invalidate(String key);

    /**
     * Logs a summary of cache statistics. No-op on the NoOpCache.
     */
    void logStats();
}
