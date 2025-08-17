package com.bhav.gecko.dto;

public class GetResponse {
    private String key;
    private String value;
    private long timestamp;
    private boolean deleted;

    public GetResponse(String key, String value, long timestamp, boolean deleted) {
        this.key = key;
        this.value = value;
        this.timestamp = timestamp;
        this.deleted = deleted;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isDeleted() {
        return deleted;
    }
}