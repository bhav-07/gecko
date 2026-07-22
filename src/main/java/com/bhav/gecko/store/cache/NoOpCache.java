package com.bhav.gecko.store.cache;

import com.bhav.gecko.store.memtable.MemTableRecord;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Null Object implementation of ReadCache.
 *
 * Used when read.cache.enabled=false. Every method is a silent no-op so
 * callers in DiskStoreServiceImpl need zero if-checks — they just call the
 * interface and this implementation quietly does nothing.
 */
public class NoOpCache implements ReadCache {

    private static final Log logger = LogFactory.getLog(NoOpCache.class);

    public NoOpCache() {
        logger.info("Read cache is DISABLED — using NoOpCache");
    }

    @Override
    public MemTableRecord get(String key) {
        return null;
    }

    @Override
    public void put(String key, MemTableRecord record) {
        // intentionally empty
    }

    @Override
    public void invalidate(String key) {
        // intentionally empty
    }

    @Override
    public void logStats() {
        logger.info("Read cache is disabled — no stats available");
    }
}
