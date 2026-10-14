package com.rnd.bridge.storage;

import java.util.Map;
import java.util.Optional;

/**
 * Generic object-storage contract shared by every raw-landing lane (Malki's intake lane,
 * Esther's media lane) so both can land bytes through the same client without depending on
 * either lane's own record types (SourceRecord, or media's future equivalent).
 */
public interface ObjectStorageClient {

    PutResult putObject(String objectKey, byte[] content, String contentType, Map<String, String> tags);

    /** Empty when the object doesn't exist (e.g. no cursor has been saved yet for a source/tenant). */
    Optional<byte[]> getObject(String objectKey);

    record PutResult(String contentType, long sizeBytes) {
    }
}
