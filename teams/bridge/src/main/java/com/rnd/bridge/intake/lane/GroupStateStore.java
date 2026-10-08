package com.rnd.bridge.intake.lane;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.LandedAttachment;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.storage.ControlPlaneStateStore;
import com.rnd.bridge.storage.ObjectStorageClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Qualifier;
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
            new GroupState(null, null, null, null, null, null, List.of(), false, -1, null);

    /**
     * Reference sentinel for "no current-chat pointer persisted yet" - same reference-identity
     * convention as {@link #MISSING_GROUP_SENTINEL}, distinguishing a genuinely missing pointer
     * from a legitimate {@link OpenChatPointer} by identity alone (see
     * {@link #resolveOpenChatGroupAndMerge}).
     */
    private static final OpenChatPointer NO_OPEN_CHAT_POINTER = new OpenChatPointer(null);

    private final ObjectStorageClient storageClient;
    private final ControlPlaneStateStore controlPlaneStateStore;
    private final ObjectMapper objectMapper;

    public GroupStateStore(
            @Qualifier("postgresObjectStorageClient") ObjectStorageClient storageClient,
            ControlPlaneStateStore controlPlaneStateStore, ObjectMapper objectMapper) {
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
     * not-yet-actually-published revision was confirmed. This still works with revision numbers
     * that collapse across multiple pre-publish edits (see ChatGroupStateReconciler.
     * mergeIntoGroupState): an edit landing while a revision is actively claimed for publication
     * always advances to a new revision number rather than collapsing (see that method's
     * javadoc), so a stale ack for the claimed revision still mismatches the edited content's now
     * newly-advanced revision, exactly as before.
     * The check-and-flip runs atomically via update() so a concurrent mergeAndMaybePublish can't
     * land between the check and the write.
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
            if (current == MISSING_GROUP_SENTINEL || current.revision() != expectedRevision || current.published()
                    || current.superseded()) {
                return current;
            }
            return new GroupState(
                    current.groupId(), current.sourceType(), current.tenantId(), current.chatId(),
                    current.threadId(), current.correlationId(), current.messages(), true, current.revision(), null,
                    current.superseded());
        });
    }

    /**
     * Records one specific attachment (identified by its owning message's id plus Slack's own
     * stable file id - see ChatMessage.Attachment.fileId/SourceRecord.attachmentFileId) as
     * already landed to MinIO, so a later Slack retry or an unrelated edit of the same message
     * can recognize it was already landed and skip re-landing it (see
     * ChatGroupStateReconciler.alreadyLanded/MessageSnapshot.landedAttachments). This is MinIO-
     * landing state, not Kafka-publication state: a non-thread session's attachments and
     * transcript all publish together, exactly once, at session close (see SlackSessionPublisher)
     * - there is no longer an independent per-attachment Kafka confirmation to track.
     * Deliberately not revision-guarded like confirmPublished: a specific attachment's own landed
     * state is independent of the group's current revision - it marks "this file, on this
     * message, was landed", which stays true regardless of what the group's transcript later
     * becomes. If the message no longer exists in the group, or already lists this fileId, this
     * is a no-op - update()'s mutator returns the same messages list unchanged so no write
     * happens.
     * Same not-found tolerance as confirmPublished for a group that genuinely isn't there.
     */
    public void recordLandedAttachment(
            RawSourceType sourceType, String tenantId, String groupId, String messageId, String fileId,
            RawObjectRef ref) {
        if (load(sourceType, tenantId, groupId).isEmpty()) {
            return;
        }
        update(sourceType, tenantId, groupId, MISSING_GROUP_SENTINEL, current -> {
            if (current == MISSING_GROUP_SENTINEL) {
                return current;
            }
            List<MessageSnapshot> updatedMessages = current.messages().stream()
                    .map(snapshot -> snapshot.messageId().equals(messageId)
                            && snapshot.landedAttachments().stream().noneMatch(la -> la.fileId().equals(fileId))
                                    ? withLandedAttachment(snapshot, fileId, ref)
                                    : snapshot)
                    .toList();
            if (updatedMessages.equals(current.messages())) {
                return current;
            }
            return new GroupState(
                    current.groupId(), current.sourceType(), current.tenantId(), current.chatId(),
                    current.threadId(), current.correlationId(), updatedMessages, current.published(),
                    current.revision(), current.claimedRevision(), current.superseded());
        });
    }

    private static MessageSnapshot withLandedAttachment(MessageSnapshot snapshot, String fileId, RawObjectRef ref) {
        List<LandedAttachment> landed = new ArrayList<>(snapshot.landedAttachments());
        landed.add(new LandedAttachment(fileId, ref));
        return new MessageSnapshot(
                snapshot.messageId(), snapshot.sender(), snapshot.timestamp(), snapshot.text(),
                snapshot.attachmentCount(), landed);
    }

    /**
     * Atomically claims expectedRevision for publication - the guard that stops two concurrent
     * execution paths (e.g. a timer callback and startup recovery, once those land) from both
     * publishing the same time-gap session. Returns true only if expectedRevision is still the
     * group's current revision, that revision is not already published, and it is not already
     * claimed (by this or a prior caller) - i.e. exactly one caller ever wins a given revision's
     * claim. On success the claim is persisted as claimedRevision before this method returns.
     * The check-and-flip runs atomically via update() so a concurrent mergeAndMaybePublish or
     * another claimForPublication call for the same key can't land in between.
     * Deliberately does not hold any lock past this call: the caller is expected to release
     * (implicitly, by this method returning) before doing the actual Kafka publish, then call
     * confirmPublished(expectedRevision) once that publish succeeds - see this class's and
     * confirmPublished's javadoc for why the persisted claim, not a held lock, is what prevents
     * a second publish attempt in between.
     * A no-op (returns false) if the group isn't found at all, same reasoning as
     * confirmPublished's precheck.
     */
    public boolean claimForPublication(RawSourceType sourceType, String tenantId, String groupId, int expectedRevision) {
        if (load(sourceType, tenantId, groupId).isEmpty()) {
            return false;
        }
        boolean[] claimed = {false};
        update(sourceType, tenantId, groupId, MISSING_GROUP_SENTINEL, current -> {
            if (current == MISSING_GROUP_SENTINEL
                    || current.revision() != expectedRevision
                    || current.published()
                    || current.superseded()
                    || (current.claimedRevision() != null && current.claimedRevision() == expectedRevision)) {
                return current;
            }
            claimed[0] = true;
            return new GroupState(
                    current.groupId(), current.sourceType(), current.tenantId(), current.chatId(),
                    current.threadId(), current.correlationId(), current.messages(), current.published(),
                    current.revision(), expectedRevision, current.superseded());
        });
        return claimed[0];
    }

    /**
     * Marks a group as superseded - its content has been migrated into a different group's
     * identity (today: a non-thread group whose root was adopted by a newly-created thread
     * group, see ChatGroupStateReconciler.newThreadGroupState) and must never be independently
     * published again, including by a pending inactivity timer that was already scheduled before
     * the migration happened (see SlackSessionScheduler.onDeadline's superseded check).
     * Deliberately not the same as marking it published: this group's content was never actually
     * acknowledged by Kafka under its own correlationId, so published=true here would be a false
     * claim of delivery that never happened - superseded says "retired, nothing further to do",
     * independent of whether anything was ever actually sent.
     * Same not-found tolerance as confirmPublished/recordLandedAttachment for a group that
     * genuinely isn't there, and idempotent - a no-op if already superseded.
     */
    public void markSuperseded(RawSourceType sourceType, String tenantId, String groupId) {
        if (load(sourceType, tenantId, groupId).isEmpty()) {
            return;
        }
        update(sourceType, tenantId, groupId, MISSING_GROUP_SENTINEL, current -> {
            if (current == MISSING_GROUP_SENTINEL || current.superseded()) {
                return current;
            }
            return new GroupState(
                    current.groupId(), current.sourceType(), current.tenantId(), current.chatId(),
                    current.threadId(), current.correlationId(), current.messages(), current.published(),
                    current.revision(), current.claimedRevision(), true);
        });
    }

    /**
     * Atomically resolves the currently-open non-thread time-gap group for
     * (sourceType, tenantId, chatId) and lets resolveAndMerge decide what to do with it - all
     * inside ONE lock, held for the chat pointer's own object key
     * ({@code _open_chat_index/{sourceType}/{tenantId}/{chatId}.json}). This is what closes the
     * race {@link ChatGroupStateReconciler#reconcileNonThreadedMessage} otherwise has for a
     * genuinely-new (never-indexed) message: a per-group lock alone is not enough, because for a
     * brand new message nobody yet knows which groupId to lock on - the decision itself ("is
     * there still an open group for this chat, or do I mint one") is exactly what must be
     * serialized per chat, not per group.
     * resolveAndMerge receives the candidate GroupState already loaded from whatever pointer was
     * persisted (empty if there is no pointer, or the pointer refers to a group that no longer
     * exists - both treated identically, as "no current candidate", never as an error: the
     * pointer is only ever a hint, and a stale one self-heals here rather than needing a
     * separate repair path). It must return the GroupState it actually merged the incoming
     * message into (via {@link #update}, using whatever groupId it decided on - reusing the
     * candidate's, or a freshly minted one) - that update() call acquires the group's own lock
     * (a different key), which nests safely under this method's chat-pointer lock without any
     * risk of the reverse order, since nothing else in this class ever holds a group lock before
     * attempting to acquire a chat-pointer lock.
     * The chat pointer is refreshed to point at whatever groupId resolveAndMerge actually used
     * only AFTER that GroupState update has already durably persisted (see this method's own
     * body) - never the reverse. If the process crashes after the GroupState write but before
     * this pointer write, the pointer is simply left stale/missing, which the next call here
     * self-heals exactly like a first-time-ever call would (see above) - it is never treated as
     * corruption.
     */
    public GroupState resolveOpenChatGroupAndMerge(
            RawSourceType sourceType, String tenantId, String chatId,
            Function<Optional<GroupState>, GroupState> resolveAndMerge) {
        AtomicReference<GroupState> merged = new AtomicReference<>();
        controlPlaneStateStore.update(
                openChatIndexKey(sourceType, tenantId, chatId), OpenChatPointer.class, NO_OPEN_CHAT_POINTER,
                currentPointer -> {
                    Optional<GroupState> candidate = currentPointer == NO_OPEN_CHAT_POINTER
                            || currentPointer.groupId() == null
                            ? Optional.empty()
                            : load(sourceType, tenantId, currentPointer.groupId());
                    GroupState result = resolveAndMerge.apply(candidate);
                    merged.set(result);
                    return new OpenChatPointer(result.groupId());
                });
        return merged.get();
    }

    /** Writes the group itself and one messageId -> groupId index entry per message it contains. */
    public void save(GroupState state) {
        storageClient.putObject(
                groupKey(state.sourceType(), state.tenantId(), state.groupId()),
                writeValue(state), "application/json", Map.of());
        writeMessageIndex(state);
    }

    /**
     * Skips the one message whose id equals the group's own groupId - for a threaded group that
     * is always exactly the thread's root (see GroupState's javadoc: groupId equals threadId for
     * threaded groups, and a message's threadId is that root's own ts), never any other member.
     * This matters for the live Slack root -> first-reply case (see
     * ChatGroupStateReconciler.newThreadGroupState): when a root that was already ingested into
     * its own non-thread group is copied into a newly-created thread group, that thread group's
     * own persist must never re-point the root's existing index entry at itself - the original
     * non-thread group must stay that messageId's permanent index owner. Harmless for the
     * ordinary case where a thread's root and replies were always grouped together from the
     * start (e.g. the batch/poll path) - there is no other group's index entry to protect there,
     * and nothing ever looks up a threaded message's own root by messageId anyway (both
     * ChatGroupStateReconciler edit-detection paths only consult the index for threadId()==null
     * messages).
     */
    private void writeMessageIndex(GroupState state) {
        GroupPointer pointer = new GroupPointer(state.groupId());
        for (MessageSnapshot message : state.messages()) {
            if (message.messageId().equals(state.groupId())) {
                continue;
            }
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

    private String openChatIndexKey(RawSourceType sourceType, String tenantId, String chatId) {
        return "_open_chat_index/%s/%s/%s.json".formatted(sourceType.name(), tenantId, chatId);
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

    /**
     * Persisted hint for "which group is currently open for this chat" - deliberately holds
     * nothing but groupId (no lastActivityAt/revision/published copy): GroupState remains the
     * only source of truth for all of that, so this can never drift out of sync with it - see
     * {@link #resolveOpenChatGroupAndMerge}, the only place this is read or written.
     */
    public record OpenChatPointer(String groupId) {
    }
}
