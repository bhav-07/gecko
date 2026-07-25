package com.bhav.gecko.store.compaction;

import com.bhav.gecko.store.sstable.SSTable;

import java.util.List;

/**
 * Strategy interface for selecting which SSTables should be merged in a
 * compaction round.
 *
 * The CompactionEngine delegates the selection decision entirely to this
 * interface. It does not care HOW the candidates were chosen, only WHAT
 * came back. This means the merge algorithm, manifest updates, and atomic
 * swap all stay identical regardless of which strategy is active.
 * <p>
 * Current implementations:
 * - MergeAllStrategy: compacts everything once a threshold is crossed
 *
 * Future implementations:
 * - SizeTieredStrategy: groups by file size, compacts the fullest tier
 * - LeveledStrategy: pushes SSTables down numbered levels
 * <p>
 */
public interface CompactionStrategy {

    /**
     * Given the current list of active SSTables, returns the subset that
     * should be merged together in the next compaction round.
     *
     * Return an empty list if compaction should not run yet.
     * The caller (CompactionEngine) will skip the compaction if the list is empty.
     *
     * @param sstables snapshot of the current active SSTables, newest-first
     * @return the SSTables to compact, or an empty list if nothing to do
     */
    List<SSTable> selectForCompaction(List<SSTable> sstables);
}
