package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.lane.GroupStateStore;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.raw.model.RawSourceType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the initial ("nothing persisted yet") {@link GroupState} for a group
 * {@link ChatGroupStateReconciler} is about to merge into - the only place a fresh
 * correlationId is ever minted, and the only place a thread group seeds itself from a root that
 * was already ingested separately. Reads {@link GroupStateStore} for that root migration; never
 * writes (the reconciler's merge path does the actual persist).
 */
class ChatGroupFactory {

    private final GroupStateStore groupStateStore;

    ChatGroupFactory(GroupStateStore groupStateStore) {
        this.groupStateStore = groupStateStore;
    }

    /** Mints a fresh correlationId for a genuinely new group - see GroupState's javadoc for why
     * this only ever happens here (once), never on an update to an already-persisted group.
     * Starts at revision -1 (not yet a real version) so the first merge - a content change from
     * nothing to something, against an unpublished (nonexistent) version - lands at revision 0
     * via the same unpublished-collapse rule any later pre-publish edit uses (see
     * GroupStateMutator), rather than a special-cased starting value. */
    GroupState newGroupState(
            String groupId, RawSourceType sourceType, String tenantId, String chatId, String threadId) {
        return new GroupState(
                groupId, sourceType, tenantId, chatId, threadId, UUID.randomUUID().toString(), List.of(), false, -1,
                null);
    }

    /**
     * The "nothing persisted yet" value for a thread group, seeded with a copy of its root
     * message's snapshot when that root was already ingested separately - the live Slack root ->
     * first-reply case: the root arrives with no thread_ts, is ingested into its own non-thread
     * group by reconcileNonThreadedMessage, and only becomes a thread's identity once the first
     * reply names it via thread_ts. Without this, reconcileThreaded would mint a brand-new,
     * root-less thread group for that first reply, permanently splitting the conversation.
     * Only does the extra lookup when this thread group doesn't already exist
     * (groupStateStore.load(threadId) empty) - for every later message in an already-created
     * thread this returns a plain not-actually-used empty value instead (mergeInto's
     * ControlPlaneStateStore.update only ever consults emptyValue when nothing is persisted at
     * the key yet), so the root is looked up and copied exactly once, at thread creation, never
     * on every subsequent reply/edit.
     * The root's own messageId always equals the thread's groupId (see GroupState's javadoc), so
     * groupStateStore.findGroupIdForMessage(threadId) resolves to the root's CURRENT group - if
     * that is a different group than this thread's own groupId, the root was split off exactly
     * as above and its snapshot is copied in; if it resolves to threadId itself, or to nothing at
     * all, this is an ordinary thread (e.g. the batch/poll path, where ChatSessionGrouper already
     * grouped the root and its replies together from the very first run - see
     * RealSlackSourceReader's hasReplies handling) and there is nothing to migrate.
     * The copied snapshot KEEPS its landedAttachments (see migratedRootSnapshot) - those files
     * were already durably landed to MinIO before the migration, and their refs are reused
     * verbatim by ChatSourceRecordMapper.toSourceRecords(GroupState, List) when this thread
     * group is published, rather than being re-landed or dropped.
     * The original (channel) group is only ever read here (groupStateStore.load, not update) for
     * its root's message content - it is otherwise left completely untouched and keeps living
     * normally (its own timer, its own publish, open to new messages - see
     * docs/plans/slack-superseded-fix.md): the root now legitimately appears in two transcripts,
     * this thread's and the channel group's own.
     * GroupStateStore.writeMessageIndex separately guarantees that persisting this seeded thread
     * group can never overwrite the root's existing index entry (still pointing at the original
     * group) - see that method's javadoc.
     */
    GroupState newThreadGroupState(
            RawSourceType sourceType, String tenantId, String chatId, String threadId) {
        if (groupStateStore.load(sourceType, tenantId, threadId).isPresent()) {
            return newGroupState(threadId, sourceType, tenantId, chatId, threadId);
        }
        List<MessageSnapshot> seedMessages = migratedRootSnapshot(sourceType, tenantId, threadId)
                .map(List::of)
                .orElse(List.of());
        return new GroupState(
                threadId, sourceType, tenantId, chatId, threadId, UUID.randomUUID().toString(), seedMessages, false,
                -1, null);
    }

    /**
     * Resolves the root's CURRENT group via the messageId index; if that is a different
     * (non-thread) group than threadId itself, this thread is being created by migrating that
     * root's snapshot (landedAttachments included, see this method's own javadoc above) to seed
     * the new thread. The original (channel) group is left otherwise untouched - it keeps living
     * normally, see newThreadGroupState's javadoc. If the index resolves to threadId itself, or
     * to nothing at all, this is an ordinary thread (e.g. the batch/poll path, where the root and
     * replies were already grouped together from the first run) and there is nothing to migrate.
     */
    private Optional<MessageSnapshot> migratedRootSnapshot(RawSourceType sourceType, String tenantId, String threadId) {
        Optional<String> rootGroupId = groupStateStore.findGroupIdForMessage(sourceType, tenantId, threadId)
                .filter(id -> !id.equals(threadId));
        if (rootGroupId.isEmpty()) {
            return Optional.empty();
        }
        return groupStateStore.load(sourceType, tenantId, rootGroupId.get())
                .flatMap(rootGroup -> rootGroup.messages().stream()
                        .filter(snapshot -> snapshot.messageId().equals(threadId))
                        .findFirst());
    }
}
