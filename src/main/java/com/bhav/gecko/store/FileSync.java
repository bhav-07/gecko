package com.bhav.gecko.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Helpers for making file system changes durable.
 *
 * Writing to a file only puts bytes in the OS page cache. Creating or deleting
 * a file changes its parent directory, which has its own metadata. Both have to
 * be forced to disk before the engine relies on them surviving a power loss.
 */
public final class FileSync {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    private FileSync() {
    }

    /**
     * Forces a directory's entries (file creations, renames, deletions) to disk.
     * Windows does not allow opening a directory as a channel, and NTFS journals
     * directory metadata itself, so this is a no-op there.
     */
    public static void syncDirectory(Path directory) throws IOException {
        if (IS_WINDOWS) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
