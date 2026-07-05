package com.bhav.gecko.store.sstable;

public class SparseIndex {
    private Integer keySize;
    private String key;
    private Integer offsetInbytes;

    public SparseIndex(Integer keySize, String key, Integer offsetInbytes) {
        this.keySize = keySize;
        this.key = key;
        this.offsetInbytes = offsetInbytes;
    }

    public Integer getKeySize() {
        return keySize;
    }

    public void setKeySize(Integer keySize) {
        this.keySize = keySize;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Integer getOffsetInbytes() {
        return offsetInbytes;
    }

    public void setOffsetInbytes(Integer offsetInbytes) {
        this.offsetInbytes = offsetInbytes;
    }

}
