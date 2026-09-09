package com.rnd.bridge.intake.lane;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.storage.ControlPlaneStateStore;
import com.rnd.bridge.storage.ObjectStorageClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/**
 * Persists {@link GroupState} as small JSON objects in MinIO, plus a messageId -> groupId
 * lookup index — same "small JSON state object, get-by-exact-key" pattern as
 * {@link CursorStore}, chosen because {@link ObjectStorageClient} has no listing/query
 * capability: the index is one tiny object per messageId rather than one growing map, so a
 * lookup is always a single direct get, never a scan.
 * The index lets ChatGroupStateReconciler recognize a message it has already landed before
 * (an edit, or a plain re-read) regardless of which group it lives in - needed so an edited
 * message updates the right group instead of being treated as unrelated new content. It is
 * not used to decide which messages get grouped together in the first place: time-gap
 * grouping of genuinely new messages is batch-local only (see ChatGroupStateReconciler).
 * The group record's own read-modify-write cycle goes through {@link ControlPlaneStateStore}
 * (see {@link #update}), not plain load()/save(): two concurrent reconciler calls touching the
 * same groupId used to be able to both load the same stale state and have the second save()
 * silently clobber the first's messages. The index writes are not covered by that lock (see
 * {@link #update}'s javadoc) — this deliberately doesn't make the group + index update
 * transactional, same as before.
 */
@Component
public class GroupStateStore {

    /**
     * Reference sentinel used as the "empty" value where the group is expected to already
     * exist (confirmPublished, called only after this exact group was just built and handed
     * to the lane's publish step). Distinguishable from a legitimate group by reference
     * identity alone - the underlying ControlPlaneStateStore.load returns this exact instance,
     * untouched, only when nothing is persisted at that key yet.
     */
    private static final GroupState MISSING_GROUP_SENTINEL =
            new GroupState(null, null, null, null, null, null, List.of(), false, -1);

    private final ObjectStorageClient storageClient;
    private final ControlPlaneStateStore controlPlaneStateStore;
    private final ObjectMapper objectMapper;

    public GroupStateStore(
            ObjectStorageClient storageClient, ControlPlaneStateStore controlPlaneStateStore, ObjectMapper objectMapper) {
        this.storageClient = storageClient;
        this.controlPlaneStateStore = controlPlaneStateStore;
        this.objectMapper = objectMapper;
    }

    public Optional<GroupState> load(RawSourceType sourceType, String tenantId, String groupId) {
        return storageClient.getObject(groupKey(sourceType, tenantId, groupId))
                .map(bytes -> readValue(bytes, GroupState.class));
    }

    public Optional<String> findGroupIdForMessage(RawSourceType sourceType, String tenantId, String messageId) {
        return storageClient.getObject(messageIndexKey(sourceType, tenantId, messageId))
                .map(bytes -> readValue(bytes, GroupPointer.class).groupId());
    }

    /**
     * Atomically loads the group currently persisted at (sourceType, tenantId, groupId) - or
     * emptyValue if nothing is there yet - and lets mutator compute its replacement, all under
     * one ControlPlaneStateStore-held lock keyed on the group's own object key. mutator runs
     * against a value loaded at lock-acquisition time, not one the caller loaded earlier and
     * that a concurrent update to the same group could have since moved past - see
     * ChatGroupStateReconciler.mergeAndMaybePublish, the only caller, for why that distinction
     * is exactly what closes the load-then-save race.
     * The messageId -> groupId index is then updated to match the returned state - a separate,
     * unlocked write per message, same as {@link #save} always did. Not made transactional with
     * the group's own atomic update (see class javadoc).
     */
    public GroupState update(
            RawSourceType sourceType, String tenantId, String groupId, GroupState emptyValue,
            UnaryOperator<GroupState> mutator) {
        GroupState updated = controlPlaneStateStore.update(
                groupKey(sourceType, tenantId, groupId), GroupState.class, emptyValue, mutator);
        writeMessageIndex(updated);
        return updated;
    }

    /**
     * Marks a group as confirmed published - but only if expectedRevision is still the group's
     * CURRENT revision. Called once the transcript event for that specific revision actually
     * reached Kafka (see AbstractIntakeLane.afterPublish / UnstructuredIntakeLane), never by
     * ChatGroupStateReconciler itself.
     * expectedRevision matters because the ack being confirmed here was dispatched for a
     * SourceRecord built from a particular revision, and by the time the ack comes back a
     * concurrent mergeAndMaybePublish could already have superseded it (see
     * ChatGroupStateReconcilerTest.concurrentUpdatesToTheSameGroupDoNotLoseMessages's sibling
     * concern, but for publish confirmation instead of the merge itself): if the current
     * revision no longer matches, marking it published now would incorrectly claim a NEWER,
     * not-yet-actually-published revision was confirmed. The check-and-flip runs atomically via
     * update() so a concurrent mergeAndMaybePublish can't land between the check and the write.
     * A no-op if the group isn't found at all (shouldn't happen in practice - the record being
     * confirmed was built from this exact group a moment earlier in the same run), checked
     * before taking the lock: update()'s underlying ControlPlaneStateStore.update always
     * persists whatever its mutator returns, and there is no legitimate empty GroupState that
     * would be safe to write back if genuinely nothing exists yet - so confirming a
     * nonexistent group must never reach that call at all, rather than relying on the mutator
     * to turn a missing-group sentinel into a no-op.
     */
    public void confirmPublished(RawSourceType sourceType, String tenantId, String groupId, int expectedRevision) {
        if (load(sourceType, tenantId, groupId).isEmpty()) {
            return;
        }
        update(sourceType, tenantId, groupId, MISSING_GROUP_SENTINEL, current -> {
            if (current == MISSING_GROUP_SENTINEL || current.revision() != expectedRevision || current.published()) {
                return current;
            }
            return new GroupState(
                    current.groupId(), current.sourceType(), current.tenantId(), current.chatId(),
                    current.threadId(), current.correlationId(), current.messages(), true, current.revision());
        });
    }

    /** Writes the group itself and one messageId -> groupId index entry per message it contains. */
    public void save(GroupState state) {
        storageClient.putObject(
                groupKey(state.sourceType(), state.tenantId(), state.groupId()),
                writeValue(state), "application/json", Map.of());
        writeMessageIndex(state);
    }

    private void writeMessageIndex(GroupState state) {
        GroupPointer pointer = new GroupPointer(state.groupId());
        for (MessageSnapshot message : state.messages()) {
            storageClient.putObject(
                    messageIndexKey(state.sourceType(), state.tenantId(), message.messageId()),
                    writeValue(pointer), "application/json", Map.of());
        }
    }

    private String groupKey(RawSourceType sourceType, String tenantId, String groupId) {
        return "_groups/%s/%s/%s.json".formatted(sourceType.name(), tenantId, groupId);
    }

    private String messageIndexKey(RawSourceType sourceType, String tenantId, String messageId) {
        return "_message_index/%s/%s/%s.json".formatted(sourceType.name(), tenantId, messageId);
    }

    private <T> T readValue(byte[] bytes, Class<T> type) {
        try {
            return objectMapper.readValue(bytes, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse stored " + type.getSimpleName(), e);
        }
    }

    private byte[] writeValue(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new UncheckedIOException("Failed to serialize " + value.getClass().getSimpleName(), new IOException(e));
        }
    }

    private record GroupPointer(String groupId) {
    }
}
