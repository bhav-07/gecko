package com.bhav.gecko.store.cache;

import com.bhav.gecko.store.memtable.MemTableRecord;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A thread-safe Least Recently Used (LRU) read cache that sits between the
 * memtable layers and the SSTable layer in the read path.
 *
 * Backed by a LinkedHashMap in access-order mode, which automatically moves
 * every accessed entry to the tail of its internal linked list. When the map
 * exceeds its capacity, the entry at the head (least recently accessed) is
 * evicted to make room for the new one.
 *
 * All public methods are synchronized because LinkedHashMap in access-order
 * mode mutates its internal structure on get() (it reorders the linked list),
 * meaning even reads are not thread-safe without explicit synchronization.
 */
public class LRUCache implements ReadCache {

    private static final Log logger = LogFactory.getLog(LRUCache.class);

    private final int capacity;
    private final LinkedHashMap<String, MemTableRecord> cache;

    // Running stats — no need for atomics since all access is synchronized
    private long hits = 0;
    private long misses = 0;
    private long evictions = 0;
    private long invalidations = 0;

    public LRUCache(int capacity) {
        this.capacity = capacity;
        // initialCapacity = capacity+1 so the map never rehashes before we evict.
        // loadFactor = 0.75f is the standard default.
        // accessOrder = true is the magic that gives us LRU behavior.
        this.cache = new LinkedHashMap<>(capacity + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, MemTableRecord> eldest) {
                if (size() > capacity) {
                    evictions++;
                    logger.debug("LRU eviction: key='" + eldest.getKey()
                            + "' | cache size after eviction=" + (size() - 1)
                            + "/" + capacity);
                    return true;
                }
                return false;
            }
        };
        logger.info("LRU read cache initialized with capacity=" + capacity);
    }

    /**
     * Returns the cached record for the given key, or null on a miss.
     * A hit promotes the entry to "most recently used".
     */
    public synchronized MemTableRecord get(String key) {
        MemTableRecord record = cache.get(key);
        if (record != null) {
            hits++;
            logger.debug("Cache HIT  key='" + key + "' | hits=" + hits + " misses=" + misses);
        } else {
            misses++;
            logger.debug("Cache MISS key='" + key + "' | hits=" + hits + " misses=" + misses);
        }
        return record;
    }

    /**
     * Inserts or updates an entry. If the cache is at capacity, the least
     * recently used entry is automatically evicted before this returns.
     */
    public synchronized void put(String key, MemTableRecord record) {
        boolean isNew = !cache.containsKey(key);
        cache.put(key, record);
        if (isNew) {
            logger.debug("Cache PUT  key='" + key + "' | size=" + cache.size() + "/" + capacity);
        }
    }

    /**
     * Removes a key from the cache. Called on every write and delete so stale
     * data never gets served to readers.
     */
    public synchronized void invalidate(String key) {
        if (cache.remove(key) != null) {
            invalidations++;
            logger.debug("Cache INVALIDATE key='" + key + "' | size=" + cache.size()
                    + "/" + capacity + " | total invalidations=" + invalidations);
        }
    }

    /**
     * Empties the entire cache. Useful during testing or if the storage engine
     * needs to be reset.
     */
    public synchronized void clear() {
        int previous = cache.size();
        cache.clear();
        logger.info("Cache cleared: removed " + previous + " entries");
    }

    public synchronized int size() {
        return cache.size();
    }

    public int getCapacity() {
        return capacity;
    }

    /**
     * Logs a summary of cache statistics. Call this wherever you want a
     * periodic snapshot, e.g. a health endpoint or a scheduled task.
     */
    public synchronized void logStats() {
        long total = hits + misses;
        double hitRate = total == 0 ? 0.0 : (hits * 100.0) / total;
        logger.info(String.format(
                "LRU Cache Stats | size=%d/%d | hits=%d | misses=%d | hitRate=%.1f%% | evictions=%d | invalidations=%d",
                cache.size(), capacity, hits, misses, hitRate, evictions, invalidations));
    }
}
