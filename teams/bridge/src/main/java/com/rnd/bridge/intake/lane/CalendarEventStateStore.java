package com.rnd.bridge.intake.lane;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.intake.model.CalendarEventState;
import com.rnd.bridge.storage.ControlPlaneStateStore;
import com.rnd.bridge.storage.ObjectStorageClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/**
 * Persists {@link CalendarEventState} as one small JSON object per (tenantId, eventId) in
 * MinIO - same "small JSON state object, get-by-exact-key" pattern as {@link CursorStore} and
 * {@link GroupStateStore}. Simpler than GroupStateStore: Calendar's event.id is already the
 * direct lookup key on every poll (Google's syncToken deltas identify events by their own id),
 * so unlike Slack there is no separate messageId -> groupId index to maintain here.
 * Every read-modify-write goes through {@link ControlPlaneStateStore#update}, which holds
 * {@link com.rnd.bridge.core.KeyedLock} for the full cycle keyed on this event's own object key
 * - so two concurrent updates to the SAME eventId can't race (one always sees the other's
 * result, never a stale copy), while different eventIds use different keys and never block each
 * other.
 */
@Component
public class CalendarEventStateStore {

    /**
     * Reference sentinel used as the "empty" value where the event is expected to already
     * exist (confirmPublished, called only after this exact state was just resolved and handed
     * to the lane's publish step). Distinguishable from a legitimate state by reference identity
     * alone - the underlying ControlPlaneStateStore.load returns this exact instance, untouched,
     * only when nothing is persisted at that key yet.
     */
    private static final CalendarEventState MISSING_STATE_SENTINEL =
            new CalendarEventState(null, null, null, null, -1, false);

    private final ObjectStorageClient storageClient;
    private final ControlPlaneStateStore controlPlaneStateStore;
    private final ObjectMapper objectMapper;

    public CalendarEventStateStore(
            ObjectStorageClient storageClient, ControlPlaneStateStore controlPlaneStateStore, ObjectMapper objectMapper) {
        this.storageClient = storageClient;
        this.controlPlaneStateStore = controlPlaneStateStore;
        this.objectMapper = objectMapper;
    }

    public Optional<CalendarEventState> load(String tenantId, String eventId) {
        return storageClient.getObject(eventKey(tenantId, eventId))
                .map(bytes -> readValue(bytes, CalendarEventState.class));
    }

    /**
     * Atomically loads the state currently persisted for (tenantId, eventId) - or a fresh
     * "nothing seen yet" sentinel if nothing is there - and lets mutator compute its
     * replacement, all under one lock keyed on this event's own object key. See
     * CalendarEventStateReconciler.resolve, the only caller, for the actual first-seen/retry/
     * updated decision.
     */
    public CalendarEventState update(String tenantId, String eventId, UnaryOperator<CalendarEventState> mutator) {
        return controlPlaneStateStore.update(
                eventKey(tenantId, eventId), CalendarEventState.class, MISSING_STATE_SENTINEL, mutator);
    }

    /**
     * Marks an event's state as confirmed published - but only if expectedRevision is still its
     * CURRENT revision, exactly like GroupStateStore.confirmPublished (see its javadoc for why
     * that guard matters: a concurrent update could have already superseded the revision this
     * ack belongs to by the time the ack comes back). A no-op if nothing is persisted at all -
     * shouldn't happen in practice, since the record being confirmed was resolved from this
     * exact state a moment earlier in the same run - checked before taking the lock, the same
     * way GroupStateStore.confirmPublished does, since update() always persists whatever its
     * mutator returns and there is no legitimate empty state safe to write back if genuinely
     * nothing exists yet.
     */
    public void confirmPublished(String tenantId, String eventId, int expectedRevision) {
        if (load(tenantId, eventId).isEmpty()) {
            return;
        }
        update(tenantId, eventId, current -> {
            if (current == MISSING_STATE_SENTINEL || current.revision() != expectedRevision || current.published()) {
                return current;
            }
            return new CalendarEventState(
                    current.eventId(), current.tenantId(), current.correlationId(), current.etag(),
                    current.revision(), true);
        });
    }

    private String eventKey(String tenantId, String eventId) {
        return "_calendar_events/%s/%s.json".formatted(tenantId, eventId);
    }

    private <T> T readValue(byte[] bytes, Class<T> type) {
        try {
            return objectMapper.readValue(bytes, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse stored " + type.getSimpleName(), e);
        }
    }
}
