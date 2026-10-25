package com.rnd.bridge.intake.lane;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.intake.model.DriveFileState;
import com.rnd.bridge.storage.ControlPlaneStateStore;
import com.rnd.bridge.storage.ObjectStorageClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Persists {@link DriveFileState} as one small JSON object per (tenantId, fileId) in Postgres -
 * the direct Drive analog of {@link CalendarEventStateStore} (see its javadoc for the full
 * reasoning this mirrors): same get-by-exact-key pattern, same per-key locking via
 * {@link ControlPlaneStateStore#update}.
 */
@Component
public class DriveFileStateStore {

    private static final DriveFileState MISSING_STATE_SENTINEL =
            new DriveFileState(null, null, null, null, null, -1, false);

    private final ObjectStorageClient storageClient;
    private final ControlPlaneStateStore controlPlaneStateStore;
    private final ObjectMapper objectMapper;

    public DriveFileStateStore(
            @Qualifier("postgresObjectStorageClient") ObjectStorageClient storageClient,
            ControlPlaneStateStore controlPlaneStateStore, ObjectMapper objectMapper) {
        this.storageClient = storageClient;
        this.controlPlaneStateStore = controlPlaneStateStore;
        this.objectMapper = objectMapper;
    }

    public Optional<DriveFileState> load(String tenantId, String fileId) {
        return storageClient.getObject(fileKey(tenantId, fileId))
                .map(bytes -> readValue(bytes, DriveFileState.class));
    }

    /**
     * Atomically loads the state currently persisted for (tenantId, fileId) - or a fresh
     * "nothing seen yet" sentinel if nothing is there - and lets mutator compute its
     * replacement, all under one lock keyed on this file's own object key. See
     * DriveFileStateReconciler.resolve, the only caller, for the actual first-seen/retry/updated
     * decision.
     */
    public DriveFileState update(String tenantId, String fileId, UnaryOperator<DriveFileState> mutator) {
        return controlPlaneStateStore.update(
                fileKey(tenantId, fileId), DriveFileState.class, MISSING_STATE_SENTINEL, mutator);
    }

    /**
     * Marks a file's state as confirmed published - but only if expectedRevision is still its
     * CURRENT revision, exactly like CalendarEventStateStore.confirmPublished (see its javadoc
     * for why that guard matters). A no-op if nothing is persisted at all.
     */
    public void confirmPublished(String tenantId, String fileId, int expectedRevision) {
        if (load(tenantId, fileId).isEmpty()) {
            return;
        }
        update(tenantId, fileId, current -> {
            if (current == MISSING_STATE_SENTINEL || current.revision() != expectedRevision || current.published()) {
                return current;
            }
            return new DriveFileState(
                    current.fileId(), current.tenantId(), current.correlationId(), current.headRevisionId(),
                    current.version(), current.revision(), true);
        });
    }

    private String fileKey(String tenantId, String fileId) {
        return "_drive_files/%s/%s.json".formatted(tenantId, fileId);
    }

    private <T> T readValue(byte[] bytes, Class<T> type) {
        try {
            return objectMapper.readValue(bytes, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse stored " + type.getSimpleName(), e);
        }
    }
}
