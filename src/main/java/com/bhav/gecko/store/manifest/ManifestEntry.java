package com.bhav.gecko.store.manifest;

public class ManifestEntry {

    private final String action;
    private final int sstId;
    private final long timestamp;

    public ManifestEntry(String action, int sstId, long timestamp) {
        this.action = action;
        this.sstId = sstId;
        this.timestamp = timestamp;
    }

    /**
     * Parses a single line from the manifest file.
     * Expected format: ACTION:SST_ID:TIMESTAMP
     */
    public static ManifestEntry fromLine(String line) {
        String[] parts = line.trim().split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Malformed manifest line: " + line);
        }
        String action = parts[0];
        int sstId = Integer.parseInt(parts[1]);
        long timestamp = Long.parseLong(parts[2]);
        return new ManifestEntry(action, sstId, timestamp);
    }

    /**
     * Serializes this entry to its manifest line representation.
     */
    public String toLine() {
        return action + ":" + sstId + ":" + timestamp;
    }

    public String getAction() {
        return action;
    }

    public int getSstId() {
        return sstId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "ManifestEntry{action='" + action + "', sstId=" + sstId + ", timestamp=" + timestamp + "}";
    }
}
