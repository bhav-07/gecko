package com.bhav.gecko.exception;

/**
 * Thrown when the engine cannot give a trustworthy answer, for example when an
 * SSTable read fails or a record fails its checksum. Surfacing this as an error is
 * deliberate: treating it as a miss would fall through to older data and return a
 * stale value.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
