package com.bhav.gecko.store.compaction;

import com.bhav.gecko.store.sstable.SSTable;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Compaction strategy that selects ALL active SSTables for merging once the
 * total count reaches the configured threshold.
 *
 * This is the simplest correct strategy. When enough SSTables have accumulated,
 * every single one gets merged into one new SSTable. The result is:
 *   - Only 1 SSTable on disk after each compaction round
 *   - Maximum space reclamation (all duplicates resolved, tombstones consolidated)
 *   - The highest possible write amplification (every byte gets rewritten)
 *
 * Best suited for write-heavy workloads where you want simple, predictable
 * behavior over fine-grained tuning.
 */
public class MergeAllStrategy implements CompactionStrategy {

    private static final Log logger = LogFactory.getLog(MergeAllStrategy.class);

    private final int threshold;

    public MergeAllStrategy(int threshold) {
        this.threshold = threshold;
        logger.info("MergeAllStrategy initialized with threshold=" + threshold);
    }

    /**
     * Returns ALL active SSTables when the total count reaches the threshold.
     * Returns an empty list when there are not enough SSTables yet.
     */
    @Override
    public List<SSTable> selectForCompaction(List<SSTable> sstables) {
        if (sstables.size() < threshold) {
            logger.debug("Compaction skipped: " + sstables.size() + " SSTables < threshold " + threshold);
            return Collections.emptyList();
        }

        logger.info("MergeAllStrategy: selecting all " + sstables.size()
                + " SSTables for compaction (threshold=" + threshold + ")");
        return new ArrayList<>(sstables);
    }

    public int getThreshold() {
        return threshold;
    }
}
