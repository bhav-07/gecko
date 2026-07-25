package com.bhav.gecko.store.compaction;

import com.bhav.gecko.store.manifest.Manifest;
import com.bhav.gecko.store.memtable.MemTableRecord;
import com.bhav.gecko.store.sstable.SSTable;
import com.bhav.gecko.store.sstable.SSTableIterator;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BiConsumer;

/**
 * Runs Size-Tiered Compaction for Gecko's LSM-tree storage engine.
 *
 * <p>
 * Responsibilities:
 * 1. Ask the CompactionStrategy which SSTables to merge
 * 2. Perform a k-way merge of those SSTables into one new SSTable
 * 3. Record the result in the Manifest (ADD new, REMOVE old)
 * 4. Atomically swap the in-memory SSTable list via a callback
 * 5. Delete the old SSTable files from disk
 * </p>
 * The selection logic is fully delegated to CompactionStrategy, so the engine
 * itself never changes when you switch from MergeAllStrategy to
 * SizeTieredStrategy.
 *
 * Thread safety: intended to run on a dedicated single-threaded executor.
 * The atomic swap callback is responsible for synchronization with the read
 * path.
 */
public class CompactionEngine {

    private static final Log logger = LogFactory.getLog(CompactionEngine.class);

    private final String sstDir;
    private final Manifest manifest;
    private final CompactionStrategy strategy;

    public CompactionEngine(String sstDir, Manifest manifest, CompactionStrategy strategy) {
        this.sstDir = sstDir;
        this.manifest = manifest;
        this.strategy = strategy;
        logger.info("CompactionEngine initialized with strategy: "
                + strategy.getClass().getSimpleName());
    }

    /**
     * Entry point called after every successful flush.
     *
     * Asks the strategy whether compaction should run. If yes, runs the full
     * compaction lifecycle. If the strategy returns an empty list, does nothing.
     *
     * @param currentSSTables snapshot of the current active SSTable list
     * @param swapFn          callback that atomically replaces old SSTables
     *                        with the new merged one in DiskStoreServiceImpl
     */
    public void maybeCompact(List<SSTable> currentSSTables,
            BiConsumer<List<SSTable>, SSTable> swapFn) {
        List<SSTable> candidates = strategy.selectForCompaction(currentSSTables);

        if (candidates.isEmpty()) {
            return; // strategy said nothing to do yet
        }

        logger.info("Compaction triggered: merging " + candidates.size() + " SSTables");
        long startMs = System.currentTimeMillis();

        SSTable merged = null;
        try {
            // Step 1: k-way merge of all candidate SSTables into one
            merged = kWayMerge(candidates);

            // Step 2: Persist the decision to the manifest.
            // ADD first, then REMOVEs. If we crash between them, both the
            // new and old SSTables are visible, reads return correct data.
            manifest.addEntry(merged.getSstCounter());
            for (SSTable old : candidates) {
                manifest.removeEntry(old.getSstCounter());
            }

            // Step 3: Atomically swap the in-memory list.
            // After this point, readers will use the new merged SSTable.
            swapFn.accept(candidates, merged);

            // Step 4: Delete old files from disk.
            // Safe to do now, readers no longer hold references to these.
            for (SSTable old : candidates) {
                try {
                    old.deleteFiles(sstDir);
                } catch (IOException e) {
                    // Orphaned files are harmless. Log and continue.
                    logger.error("Could not delete files for sst_" + old.getSstCounter()
                            + ": " + e.getMessage());
                }
            }

            long elapsed = System.currentTimeMillis() - startMs;
            logger.info("Compaction complete: " + candidates.size()
                    + " SSTables - sst_" + merged.getSstCounter()
                    + " in " + elapsed + "ms");

        } catch (Exception e) {
            logger.error("Compaction failed: " + e.getMessage(), e);
            // The old SSTables are still active, reads continue to work.
            // The merged SSTable (if partially written) is an orphan on disk,
            // harmless until the next compaction or startup cleanup.
        }
    }

    // -----------------------------------------------------------------------
    // K-Way Merge
    // -----------------------------------------------------------------------

    /**
     * Merges N sorted SSTables into one sorted SSTable using a min-heap.
     *
     * For duplicate keys across SSTables, the version from the SSTable with
     * the highest counter (newest) wins. All other versions are discarded.
     * Tombstones are kept in the merged output, they are not dropped here
     * since older data outside the merge set may still exist on disk.
     * 
     * <p>
     * Algorithm:
     * 1. Create one SSTableIterator per input SSTable
     * 2. Seed a PriorityQueue with the first record from each iterator. PQ is
     * ordered: key ASC, then sstCounter DESC
     * 3. Poll the PQ, output the record if the key is new, skip if duplicate
     * 4. Advance the source iterator and re-insert the next record into the PQ
     * 5. Repeat until the PQ is empty
     * </p>
     */
    private SSTable kWayMerge(List<SSTable> candidates) throws IOException {
        List<SSTableIterator> iterators = new ArrayList<>();
        for (SSTable sst : candidates) {
            iterators.add(new SSTableIterator(sst));
        }

        // Comparator: sort by key ascending; for same key, prefer higher sstCounter
        // (newer SSTable) so the correct version comes out of the PQ first.
        PriorityQueue<IteratorEntry> pq = new PriorityQueue<>(
                Comparator.<IteratorEntry, String>comparing(e -> e.record.getKey())
                        .thenComparing(Comparator.comparingInt((IteratorEntry e) -> e.sstCounter).reversed()));

        // Seed the PQ with the first record from every iterator
        for (SSTableIterator it : iterators) {
            if (it.hasNext()) {
                pq.offer(new IteratorEntry(it.peek(), it, it.getSst().getSstCounter()));
            }
        }

        List<MemTableRecord> output = new ArrayList<>();
        String lastOutputKey = null;

        while (!pq.isEmpty()) {
            IteratorEntry entry = pq.poll();

            // Consume the peeked record from the iterator
            entry.iterator.next();

            String currentKey = entry.record.getKey();

            if (!currentKey.equals(lastOutputKey)) {
                // First time we see this key, it is the newest version because the PQ
                // puts higher sstCounter first for equal keys.
                output.add(entry.record);
                lastOutputKey = currentKey;
            }
            // else: duplicate key from an older SSTable, skip it

            // Re-insert the next record from this iterator if available
            if (entry.iterator.hasNext()) {
                pq.offer(new IteratorEntry(
                        entry.iterator.peek(),
                        entry.iterator,
                        entry.sstCounter));
            }
        }

        logger.info("k-way merge complete: " + output.size() + " records in merged output");
        return SSTable.initSSTableOnDisk(output, sstDir);
    }

    // -----------------------------------------------------------------------
    // Inner class
    // -----------------------------------------------------------------------

    /**
     * One entry in the PriorityQueue during the k-way merge.
     * Holds the current (peeked) record, the iterator it came from, and the
     * SSTable ID used as a tiebreaker when two iterators have the same key.
     */
    private static class IteratorEntry {
        final MemTableRecord record;
        final SSTableIterator iterator;
        final int sstCounter;

        IteratorEntry(MemTableRecord record, SSTableIterator iterator, int sstCounter) {
            this.record = record;
            this.iterator = iterator;
            this.sstCounter = sstCounter;
        }
    }

    public CompactionStrategy getStrategy() {
        return strategy;
    }
}
