package com.rnd.bridge.storage;

import java.util.Map;

/**
 * Generic object-storage contract shared by every raw-landing lane (Malki's intake lane,
 * Esther's media lane) so both can land bytes through the same client without depending on
 * either lane's own record types (SourceRecord, or media's future equivalent).
 */
public interface ObjectStorageClient {

    PutResult putObject(String objectKey, byte[] content, String contentType, Map<String, String> tags);

    record PutResult(String contentType, long sizeBytes) {
    }
}
