package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.lane.GroupStateStore;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.LandedAttachment;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.raw.model.RawSourceType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The merge/remove engine for {@link ChatGroupStateReconciler}: applies one content change to a
 * single group under {@link GroupStateStore}'s per-groupId lock, following the shared
 * revision/claim rule (see {@link #rebuild}). Both an incoming-message merge and a message
 * removal are the same kind of content change against the same lock - only their input differs -
 * so they live together here and share one revision rule instead of duplicating it.
 */
class GroupStateMutator {

    /**
     * Reference sentinel passed as the "empty" value for a path where the group is expected to
     * already exist (its groupId came from the messageId index). Distinguishable from a
     * legitimate new/empty group by reference identity alone - GroupStateStore.update's
     * underlying ControlPlaneStateStore.load returns this exact instance, untouched, only when
     * nothing is persisted at that key yet.
     */
    static final GroupState MISSING_GROUP_SENTINEL =
            new GroupState(null, null, null, null, null, null, List.of(), false, -1, null);

    private final GroupStateStore groupStateStore;

    GroupStateMutator(GroupStateStore groupStateStore) {
        this.groupStateStore = groupStateStore;
    }

    /**
     * Loads the group currently persisted at (sourceType, tenantId, groupId) - or emptyValue if
     * nothing is there yet - under GroupStateStore's per-groupId lock, merges incoming into it,
     * and writes the result back. The mutator runs against the value loaded at lock-acquisition
     * time, not one the caller loaded earlier, which is what actually prevents two concurrent
     * reconciler calls touching the same group from each merging against a stale copy.
     * confirmedUnchanged is set (not returned) because only the caller knows whether an
     * unchanged-but-confirmed result should still regenerate attachment-only records.
     */
    GroupState mergeInto(
            RawSourceType sourceType, String tenantId, String groupId, GroupState emptyValue,
            List<ChatMessage> incoming, AtomicBoolean confirmedUnchanged) {
        return groupStateStore.update(sourceType, tenantId, groupId, emptyValue, current -> {
            if (current == MISSING_GROUP_SENTINEL) {
                throw new IllegalStateException("Message index pointed to a missing group " + groupId);
            }
            List<MessageSnapshot> merged = mergeSnapshots(current.messages(), incoming);
            boolean contentUnchanged = merged.equals(current.messages());
            if (contentUnchanged && current.published()) {
                confirmedUnchanged.set(true);
                return current;
            }
            return rebuild(current, merged, contentUnchanged);
        });
    }

    /**
     * The actual per-group removal mutator, shared by removeMessage's thread/channel-group
     * lookups - see removeMessage's Invariant javadoc (an empty, revision &gt;= 0 group is only
     * ever produced here). removed is set (not returned) so a caller doing a second, channel-group
     * removal for the same messageId can track it independently of the first. A messageId not
     * present in the group is a no-op: the same messages list is returned, so no write happens.
     */
    GroupState removeFrom(
            RawSourceType sourceType, String tenantId, String groupId, String messageId, AtomicBoolean removed) {
        return groupStateStore.update(
                sourceType, tenantId, groupId, MISSING_GROUP_SENTINEL, current -> {
                    if (current == MISSING_GROUP_SENTINEL) {
                        return current;
                    }
                    if (current.messages().stream().noneMatch(m -> m.messageId().equals(messageId))) {
                        return current;
                    }
                    removed.set(true);
                    List<MessageSnapshot> remaining = current.messages().stream()
                            .filter(m -> !m.messageId().equals(messageId))
                            .toList();
                    // Deletion is not a different kind of change, just another input to it - same
                    // revision-advance rule as any merge (always a content change here).
                    return rebuild(current, remaining, false);
                });
    }

    /**
     * The shared revision/claim rule, computed in exactly one place for both {@link #mergeInto}
     * and {@link #removeFrom} (it used to be duplicated across them):
     * - unchanged content: revision untouched, any outstanding claim preserved.
     * - changed content: advances the revision only if the version being replaced was already
     *   confirmed published OR is currently claimed for publication - any number of edits against
     *   a version never confirmed and not claimed collapse onto that same revision number, since
     *   there is no outstanding confirmed-or-in-flight candidate whose identity a new version
     *   would need to avoid colliding with. Once confirmed, or claimed (and therefore possibly
     *   already in flight to Kafka), the next content change is a genuinely new version and must
     *   not share its revision number with that now-superseded candidate - sharing it would let a
     *   later stale ACK for the old candidate incorrectly confirm the edited content instead (see
     *   ChatGroupStateReconcilerTest.theStaleAckForThePreEditRevisionCannotMarkTheEditedRevisionPublished
     *   and SlackSessionSchedulerPublishConfirmationTest.editArrivingWhileRIsInFlight...).
     * claimedRevision is always cleared on any content change regardless of which branch this is -
     * a separate concern: this decides whether the number moves, that decides whether an
     * outstanding claim survives. The result is always persisted as pending (published=false).
     */
    private GroupState rebuild(GroupState current, List<MessageSnapshot> messages, boolean contentUnchanged) {
        boolean supersedesAnOutstandingVersion = current.published() || current.claimedRevision() != null;
        int revision = contentUnchanged
                ? current.revision()
                : supersedesAnOutstandingVersion ? current.revision() + 1 : Math.max(current.revision(), 0);
        Integer claimedRevision = contentUnchanged ? current.claimedRevision() : null;
        return new GroupState(
                current.groupId(), current.sourceType(), current.tenantId(), current.chatId(),
                current.threadId(), current.correlationId(), messages, false, revision, claimedRevision);
    }

    /** Merge by messageId (same id = edit, replace text; new id = append), then re-sort by
     * source timestamp so the rebuilt transcript stays in the order messages actually occurred.
     * A replaced snapshot carries forward whichever of its previously landedAttachments are
     * still among the incoming message's current attachments (see
     * MessageSnapshot.landedAttachments) - a Slack retry or a text-only edit of an already-landed
     * attachment must not forget it was already landed, but a fileId that's no longer attached
     * (removed by the edit) is simply dropped rather than kept as stale state for an attachment
     * that no longer exists on this message. */
    private List<MessageSnapshot> mergeSnapshots(List<MessageSnapshot> existing, List<ChatMessage> incoming) {
        Map<String, MessageSnapshot> byId = new LinkedHashMap<>();
        for (MessageSnapshot snapshot : existing) {
            byId.put(snapshot.messageId(), snapshot);
        }
        for (ChatMessage message : incoming) {
            List<String> currentFileIds = message.attachments().stream()
                    .map(ChatMessage.Attachment::fileId)
                    .filter(fileId -> fileId != null)
                    .toList();
            MessageSnapshot previous = byId.get(message.id());
            List<LandedAttachment> carriedLandedAttachments = previous == null
                    ? List.of()
                    : previous.landedAttachments().stream()
                            .filter(la -> currentFileIds.contains(la.fileId()))
                            .toList();
            byId.put(message.id(), new MessageSnapshot(
                    message.id(), message.sender(), message.timestamp(), message.text(),
                    message.attachments().size(), carriedLandedAttachments));
        }
        return byId.values().stream()
                .sorted((a, b) -> a.timestamp().compareTo(b.timestamp()))
                .toList();
    }
}
