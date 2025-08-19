package com.bhav.gecko.store.memtable;

public class KeyEntry {
    private int timeStamp;
    private int valuePosition;
    private int entrySize;

    public KeyEntry(int timeStamp, int valuePosition, int entrySize) {
        this.timeStamp = timeStamp;
        this.valuePosition = valuePosition;
        this.entrySize = entrySize;
    }

    public int getTimeStamp() {
        return timeStamp;
    }

    public void setTimeStamp(int timeStamp) {
        this.timeStamp = timeStamp;
    }

    public int getValuePosition() {
        return valuePosition;
    }

    public void setValuePosition(int valuePosition) {
        this.valuePosition = valuePosition;
    }

    public int getEntrySize() {
        return entrySize;
    }

    public void setEntrySize(int entrySize) {
        this.entrySize = entrySize;
    }
}