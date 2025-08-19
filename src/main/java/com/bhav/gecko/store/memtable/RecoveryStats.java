package com.bhav.gecko.store.memtable;

public class RecoveryStats {
    private int recoveredEntries;
    private int currentSizeInBytes;
    private boolean hasUnflushedWALData;

    public RecoveryStats(int recoveredEntries, int currentSizeInBytes, boolean hasUnflushedWALData) {
        this.recoveredEntries = recoveredEntries;
        this.currentSizeInBytes = currentSizeInBytes;
        this.hasUnflushedWALData = hasUnflushedWALData;
    }

    public int getRecoveredEntries() {
        return recoveredEntries;
    }

    public int getCurrentSizeInBytes() {
        return currentSizeInBytes;
    }

    public boolean isHasUnflushedWALData() {
        return hasUnflushedWALData;
    }
}