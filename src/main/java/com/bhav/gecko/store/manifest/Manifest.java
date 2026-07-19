package com.bhav.gecko.store.manifest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Manifest tracks which SSTables are currently active in the database.
 *
 * It is the single source of truth for SSTable state across server restarts.
 * The manifest file is append-only and crash-safe — every write is fsynced
 * to disk before the corresponding WAL segment is deleted.
 *
 * File format (one line per event):
 *   ACTION:SST_ID:TIMESTAMP
 *
 * Example:
 *   ADD:1:1753012345678
 *   ADD:2:1753012346000
 *   REMOVE:1:1753012399000   <- used later during compaction
 */
public class Manifest {

    private static final Log logger = LogFactory.getLog(Manifest.class);
    private static final String MANIFEST_FILENAME = "gecko.manifest";
    private static final String ACTION_ADD = "ADD";
    private static final String ACTION_REMOVE = "REMOVE";

    private final String manifestPath;
    private final List<ManifestEntry> entries = new ArrayList<>();

    public Manifest(String sstDirectory) {
        this.manifestPath = sstDirectory + File.separator + MANIFEST_FILENAME;
    }

    /**
     * Reads the manifest file from disk and populates the in-memory entries list.
     * If the file doesn't exist, this is treated as a fresh database with no SSTables.
     * Malformed lines are logged and skipped rather than crashing the server.
     */
    public void load() throws IOException {
        Path path = Paths.get(manifestPath);
        if (!Files.exists(path)) {
            logger.info("No manifest file found — starting fresh database");
            return;
        }

        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                ManifestEntry entry = ManifestEntry.fromLine(line);
                entries.add(entry);
            } catch (Exception e) {
                // A malformed line could be from a crash mid-write. Log and skip it.
                logger.warn("Skipping malformed manifest line: '" + line + "' — " + e.getMessage());
            }
        }

        logger.info("Manifest loaded: " + entries.size() + " entries, "
                + getActiveSSTIds().size() + " active SSTables");
    }

    /**
     * Appends a new ADD entry to the manifest file for a freshly flushed SSTable.
     * This is the crash-safe commit point — we fsync the manifest before the caller
     * deletes the WAL segment.
     */
    public void addEntry(int sstCounter) throws IOException {
        ManifestEntry entry = new ManifestEntry(ACTION_ADD, sstCounter, System.currentTimeMillis());
        appendToDisk(entry);
        entries.add(entry);
        logger.info("Manifest: recorded ADD for sst_" + sstCounter);
    }

    /**
     * Appends a REMOVE entry for an SSTable that has been merged away during compaction.
     * Reserved for future use — not called yet.
     */
    public void removeEntry(int sstCounter) throws IOException {
        ManifestEntry entry = new ManifestEntry(ACTION_REMOVE, sstCounter, System.currentTimeMillis());
        appendToDisk(entry);
        entries.add(entry);
        logger.info("Manifest: recorded REMOVE for sst_" + sstCounter);
    }

    /**
     * Returns the IDs of all currently active SSTables by replaying the log:
     * an ID is active if it has been ADD-ed and not subsequently REMOVE-d.
     * The returned list is in insertion order — oldest first — which is the
     * correct order to add them to DiskStoreServiceImpl.sstables so that
     * sstables.add(0, sst) during future flushes always puts the newest first.
     */
    public List<Integer> getActiveSSTIds() {
        List<Integer> active = new ArrayList<>();
        for (ManifestEntry entry : entries) {
            if (ACTION_ADD.equals(entry.getAction())) {
                active.add(entry.getSstId());
            } else if (ACTION_REMOVE.equals(entry.getAction())) {
                active.remove(Integer.valueOf(entry.getSstId()));
            }
        }
        return active;
    }

    /**
     * Returns the highest SSTable ID currently tracked by the manifest.
     * Used on startup to initialize the SSTable counter so new flushes
     * don't collide with existing filenames.
     * Returns 0 if no SSTables have been recorded yet.
     */
    public int getMaxSSTId() {
        return entries.stream()
                .filter(e -> ACTION_ADD.equals(e.getAction()))
                .mapToInt(ManifestEntry::getSstId)
                .max()
                .orElse(0);
    }

    // -----------------------------------------------------------------------
    // Private Helpers
    // -----------------------------------------------------------------------

    /**
     * Appends a single entry line to the manifest file and fsyncs it to disk.
     * This guarantees the entry survives a power failure immediately after the write.
     */
    private void appendToDisk(ManifestEntry entry) throws IOException {
        Path path = Paths.get(manifestPath);
        // Ensure the parent directory exists
        Files.createDirectories(path.getParent());

        String line = entry.toLine() + System.lineSeparator();
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);

        try (FileOutputStream fos = new FileOutputStream(manifestPath, /* append= */ true)) {
            fos.write(bytes);
            fos.flush();
            // Critical: force the OS page cache to flush to physical disk before we
            // return to the caller, who will then delete the WAL segment.
            fos.getFD().sync();
        }
    }

    public String getManifestPath() {
        return manifestPath;
    }

    public List<ManifestEntry> getEntries() {
        return new ArrayList<>(entries);
    }
}
