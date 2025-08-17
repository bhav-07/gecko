package com.bhav.gecko.dto;

public class MemtableStats {
    private int entryCount;
    private int sizeInBytes;
    private boolean isEmpty;

    public MemtableStats(int entryCount, int sizeInBytes, boolean isEmpty) {
        this.entryCount = entryCount;
        this.sizeInBytes = sizeInBytes;
        this.isEmpty = isEmpty;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public int getSizeInBytes() {
        return sizeInBytes;
    }

    public boolean isEmpty() {
        return isEmpty;
    }
}