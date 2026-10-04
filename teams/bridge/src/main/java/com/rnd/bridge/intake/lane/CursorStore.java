package com.rnd.bridge.intake.lane;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.storage.ObjectStorageClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Persists the last {@link ReadCursor} per (sourceType, tenantId) as a small JSON object in
 * MinIO — see plan.md "Cursor persistence". Storage-only today: no Real...SourceReader honors
 * ReadCursor.since yet, so nothing calls save()/load() outside tests until a source's reader
 * implements since-filtering (stage 2, per plan.md).
 */
@Component
public class CursorStore {

    private final ObjectStorageClient storageClient;
    private final ObjectMapper objectMapper;

    public CursorStore(ObjectStorageClient storageClient, ObjectMapper objectMapper) {
        this.storageClient = storageClient;
        this.objectMapper = objectMapper;
    }

    public ReadCursor load(RawSourceType sourceType, String tenantId) {
        return storageClient.getObject(cursorKey(sourceType, tenantId))
                .map(this::readCursor)
                .orElseGet(ReadCursor::full);
    }

    public void save(RawSourceType sourceType, String tenantId, ReadCursor cursor) {
        storageClient.putObject(
                cursorKey(sourceType, tenantId), writeCursor(cursor), "application/json", Map.of());
    }

    private String cursorKey(RawSourceType sourceType, String tenantId) {
        return "_cursors/%s/%s.json".formatted(sourceType.name(), tenantId);
    }

    private ReadCursor readCursor(byte[] bytes) {
        try {
            return objectMapper.readValue(bytes, ReadCursor.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse stored cursor", e);
        }
    }

    private byte[] writeCursor(ReadCursor cursor) {
        try {
            return objectMapper.writeValueAsBytes(cursor);
        } catch (Exception e) {
            throw new UncheckedIOException("Failed to serialize cursor", new IOException(e));
        }
    }
}
