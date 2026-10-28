package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.lane.GroupStateStore;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.LandedAttachment;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.RawSourceType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

/**
 * The stateful counterpart to ChatSessionGrouper: recognizes when an incoming batch of chat
 * messages belongs to a group already persisted from a previous intake run, and extends that
 * same group instead of creating an unrelated one. Only wired for sources with a stable
 * per-message id (today: Slack) — WhatsApp/fixtures have no id to match on, so they keep
 * calling ChatSessionGrouper.groupIntoSessions() directly via SourceRecordLoader, unchanged.
 * Two independent matching strategies, mirroring ChatSessionGrouper's own threaded/time-gap
 * split (groupByThread/partitionByTimeGap, reused here instead of reimplemented):
 * - threaded: groupId == threadId always, so a reply is found by a single direct
 *   GroupStateStore.load() keyed on its own threadId — no cross-run lookup needed. Thread
 *   continuation works across intake runs regardless of how much time has passed.
 * - time-gap: groupId is an opaque UUID minted fresh per session. A message can still be
 *   recognized as an edit of one already persisted (GroupStateStore.findGroupIdForMessage,
 *   keyed by the message's own id — needed so an edited message updates the right group
 *   instead of being treated as new content). But time-gap *grouping* itself — deciding which
 *   messages belong together in the first place — is deliberately best-effort within the
 *   current intake batch only (see groupNewTimeGapMessages): a genuinely new (never-seen)
 *   message always starts or extends a group using only messages from this same batch, never
 *   by reconnecting to a group a previous run happened to leave off near in time. Intake
 *   currently runs manually/infrequently, so a session split across runs is accepted for now
 *   (see plan.md); revisit once real-time ingestion makes that split less tolerable.
 * Every group touched here is merged and republished immediately (no OPEN/CLOSED waiting
 * lifecycle yet — see plan.md), except a genuine no-op (identical content re-read of an
 * already Kafka-confirmed group), which persists/republishes nothing and leaves revision
 * untouched; see mergeAndMaybePublish for the full two-phase publish state machine (this
 * class only ever persists a group as pending — GroupStateStore.confirmPublished is what
 * actually marks one published, once UnstructuredIntakeLane's afterPublish hook confirms
 * Kafka accepted the transcript event).
 * mergeAndMaybePublish's entire decision (merge incoming messages, detect no-op, compute
 * revision) runs as the mutator passed to GroupStateStore.update, which loads the current
 * GroupState under GroupStateStore/ControlPlaneStateStore's per-groupId lock at the moment the
 * mutator runs - not a value this class loaded earlier itself. That's the difference that
 * actually prevents two concurrent reconciler calls touching the same group from each merging
 * against a stale copy and one silently clobbering the other's messages; building SourceRecords
 * afterward (chatSourceRecordMapper.toSourceRecords) stays outside that lock, since it only
 * reads the already-committed result.
 * Deliberately does not attempt to resolve the case where one late message would bridge two
 * already-published groups into one — that edge case is out of scope for now.
 * groupId (threadId, or the minted time-gap UUID) is purely Bridge's own internal identity -
 * used for GroupStateStore's storage key and the messageId index, never sent downstream. What
 * Mind actually receives as correlationId is GroupState.correlationId, a separate UUID minted
 * once in newGroupState and copied forward untouched on every subsequent update in
 * mergeAndMaybePublish - see GroupState's javadoc.
 */
@Service
public class ChatGroupStateReconciler {

    /**
     * Reference sentinel passed as the "empty" value for the edit path, where the group is
     * expected to already exist (its groupId came from the messageId index). Distinguishable
     * from a legitimate new/empty group by reference identity alone - GroupStateStore.update's
     * underlying ControlPlaneStateStore.load returns this exact instance, untouched, only when
     * nothing is persisted at that key yet (see mergeAndMaybePublish).
     */
    private static final GroupState MISSING_GROUP_SENTINEL =
            new GroupState(null, null, null, null, null, null, List.of(), false, -1, null);

    private final ChatSessionProperties properties;
    private final ChatSessionGrouper chatSessionGrouper;
    private final ChatSourceRecordMapper chatSourceRecordMapper;
    private final GroupStateStore groupStateStore;

    public ChatGroupStateReconciler(
            ChatSessionProperties properties, ChatSessionGrouper chatSessionGrouper,
            ChatSourceRecordMapper chatSourceRecordMapper, GroupStateStore groupStateStore) {
        this.properties = properties;
        this.chatSessionGrouper = chatSessionGrouper;
        this.chatSourceRecordMapper = chatSourceRecordMapper;
        this.groupStateStore = groupStateStore;
    }

    public List<SourceRecord> group(List<ChatMessage> messages) {
        Map<String, List<ChatMessage>> byChat = new LinkedHashMap<>();
        for (ChatMessage message : messages) {
            byChat.computeIfAbsent(message.chatId(), id -> new ArrayList<>()).add(message);
        }

        List<SourceRecord> records = new ArrayList<>();
        for (List<ChatMessage> chatMessages : byChat.values()) {
            records.addAll(reconcileThreaded(chatMessages));
            records.addAll(reconcileTimeGap(chatMessages));
        }
        return records;
    }

    private List<SourceRecord> reconcileThreaded(List<ChatMessage> chatMessages) {
        Map<String, List<ChatMessage>> byThread = chatSessionGrouper.groupByThread(chatMessages);

        List<SourceRecord> records = new ArrayList<>();
        for (Map.Entry<String, List<ChatMessage>> thread : byThread.entrySet()) {
            String groupId = thread.getKey();
            List<ChatMessage> incoming = thread.getValue();
            ChatMessage first = incoming.get(0);
            GroupState emptyValue = newThreadGroupState(first.sourceType(), first.tenantId(), first.chatId(), groupId);
            records.addAll(mergeAndMaybePublish(first.sourceType(), first.tenantId(), groupId, emptyValue, incoming));
        }
        return records;
    }

    private List<SourceRecord> reconcileTimeGap(List<ChatMessage> chatMessages) {
        List<ChatMessage> sorted = chatMessages.stream()
                .filter(m -> m.threadId() == null)
                .sorted((a, b) -> a.timestamp().compareTo(b.timestamp()))
                .toList();
        if (sorted.isEmpty()) {
            return List.of();
        }
        RawSourceType sourceType = sorted.get(0).sourceType();
        String tenantId = sorted.get(0).tenantId();

        // Split off messages whose id already belongs to a persisted group (edits/re-reads) -
        // these are resolved directly by id. Every other message only ever starts or extends a
        // group within this same batch (see class javadoc: cross-run time-gap continuation is
        // deliberately not supported).
        Map<String, List<ChatMessage>> editsByGroupId = new LinkedHashMap<>();
        List<ChatMessage> newMessages = new ArrayList<>();
        for (ChatMessage message : sorted) {
            Optional<String> existingGroupId = message.id() == null
                    ? Optional.empty()
                    : groupStateStore.findGroupIdForMessage(sourceType, tenantId, message.id());
            if (existingGroupId.isPresent()) {
                editsByGroupId.computeIfAbsent(existingGroupId.get(), id -> new ArrayList<>()).add(message);
            } else {
                newMessages.add(message);
            }
        }

        List<SourceRecord> records = new ArrayList<>();
        for (Map.Entry<String, List<ChatMessage>> edit : editsByGroupId.entrySet()) {
            records.addAll(mergeAndMaybePublish(
                    sourceType, tenantId, edit.getKey(), MISSING_GROUP_SENTINEL, edit.getValue()));
        }
        records.addAll(groupNewTimeGapMessages(newMessages));
        return records;
    }

    /**
     * Partitions genuinely-new (never-seen-before) messages into sessions via
     * ChatSessionGrouper.partitionByTimeGap (the same run-of-consecutive-messages heuristic
     * used by its stateless path), then persists each session via mergeAndMaybePublish instead
     * of building it straight into a SourceRecord. Never looks at any previously-persisted
     * group when deciding where a session starts or ends.
     */
    private List<SourceRecord> groupNewTimeGapMessages(List<ChatMessage> newMessages) {
        List<SourceRecord> records = new ArrayList<>();
        for (List<ChatMessage> session : chatSessionGrouper.partitionByTimeGap(newMessages)) {
            records.addAll(mergeAndMaybePublishNewSession(session));
        }
        return records;
    }

    private List<SourceRecord> mergeAndMaybePublishNewSession(List<ChatMessage> session) {
        ChatMessage first = session.get(0);
        String groupId = UUID.randomUUID().toString();
        GroupState emptyValue = newGroupState(groupId, first.sourceType(), first.tenantId(), first.chatId(), null);
        return mergeAndMaybePublish(first.sourceType(), first.tenantId(), groupId, emptyValue, session);
    }

    /**
     * Merges incoming messages into the group's persisted snapshots. Reconciliation only ever
     * persists a group as pending (published=false) - it never marks anything published
     * itself. "Published" only becomes true once Kafka has actually acknowledged the
     * transcript event (see GroupStateStore.confirmPublished, called from
     * UnstructuredIntakeLane.afterPublish after a successful publish), so "already published"
     * here means "confirmed", not just "we built a record for it once".
     * revision tracks the logical content version, independent of publish confirmation (see
     * GroupState's javadoc):
     * - unchanged content, already confirmed published: the whole group (transcript + every
     *   attachment) published together as one event, so there is nothing left outstanding -
     *   mergeAndMaybePublish returns an empty list. Revision untouched.
     * - unchanged content, not yet confirmed: a retry, not a content change - rebuild and
     *   republish (transcript included) at the same revision.
     * - changed content: advances the revision only if the version being replaced was already
     *   confirmed published OR is currently claimed for publication (see
     *   GroupStateStore.claimForPublication) - any number of edits against a version that has
     *   never been confirmed published and is not currently claimed instead collapse onto that
     *   same revision number, since there is no outstanding confirmed-or-in-flight candidate yet
     *   whose identity a new version would need to avoid colliding with. Once a version has been
     *   confirmed published, or claimed and therefore possibly already in flight to Kafka, the
     *   next content change is a genuinely new version and must not share its revision number
     *   with that now-superseded candidate - sharing it would let a later stale ACK for the old
     *   (confirmed, or claimed-then-in-flight) candidate incorrectly confirm the edited content
     *   instead (see
     *   ChatGroupStateReconcilerTest.theStaleAckForThePreEditRevisionCannotMarkTheEditedRevisionPublished
     *   for the post-publish case and
     *   SlackSessionSchedulerPublishConfirmationTest.editArrivingWhileRIsInFlightGetsItsOwnScheduleAndIsUnaffectedByRsStaleConfirm
     *   for the claimed-but-not-yet-confirmed case). claimedRevision itself is still always
     *   cleared on any content change regardless of which branch this is (see below) - the two
     *   are separate concerns: this decides whether the number moves, that decides whether an
     *   outstanding claim survives.
     */
    /**
     * A logical object now publishes as exactly one RawObjectEvent (transcript + every
     * attachment bundled together), so there is no longer an independent per-attachment
     * republish to carve out here: if the group is already published and genuinely unchanged,
     * there is nothing left to do at all (an empty list) - any attachment still outstanding
     * would mean the content changed (a new/returning attachment always changes
     * mergeSnapshots' output), which is caught by the "content changed" branch below instead.
     * Otherwise (new group, content change, or an unconfirmed retry) the full current set -
     * transcript plus every attachment from incoming - is rebuilt and handed to the grouped lane
     * to land+publish together (see UnstructuredPayloadMapper/UnstructuredIntakeLane).
     */
    private List<SourceRecord> mergeAndMaybePublish(
            RawSourceType sourceType, String tenantId, String groupId, GroupState emptyValue,
            List<ChatMessage> incoming) {
        AtomicBoolean confirmedUnchanged = new AtomicBoolean(false);
        GroupState updated = mergeIntoGroupState(sourceType, tenantId, groupId, emptyValue, incoming, confirmedUnchanged);

        if (confirmedUnchanged.get()) {
            return List.of();
        }
        return chatSourceRecordMapper.toSourceRecords(updated, incoming);
    }

    /**
     * The actual merge+persist step shared by {@link #mergeAndMaybePublish} and
     * {@link #reconcileNonThreadedMessage} - loads the group currently persisted at
     * (sourceType, tenantId, groupId) under GroupStateStore's per-groupId lock, merges incoming
     * into it, and writes the result back. Pulled out on its own so the real-time path can reuse
     * the exact same merge/revision/claim bookkeeping mergeAndMaybePublish always had, without
     * also inheriting mergeAndMaybePublish's decision to build every SourceRecord (including the
     * transcript) for immediate publication - see reconcileNonThreadedMessage's javadoc for why
     * that decision must NOT be reused for a real-time non-thread message.
     * confirmedUnchanged is set (not returned) for the same reason mergeAndMaybePublish needed
     * it before this was extracted: only the caller knows whether an unchanged-but-confirmed
     * result should still regenerate attachment-only records.
     */
    private GroupState mergeIntoGroupState(
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
            boolean supersedesAnOutstandingVersion = current.published() || current.claimedRevision() != null;
            int revision = contentUnchanged
                    ? current.revision()
                    : supersedesAnOutstandingVersion ? current.revision() + 1 : Math.max(current.revision(), 0);
            Integer claimedRevision = contentUnchanged ? current.claimedRevision() : null;
            return new GroupState(
                    current.groupId(), current.sourceType(), current.tenantId(), current.chatId(),
                    current.threadId(), current.correlationId(), merged, false, revision, claimedRevision);
        });
    }

    /**
     * Real-time counterpart to {@link #group(List)} for the Slack Events API path (see
     * SlackEventsController): reconciles and persists exactly one freshly-arrived, non-thread
     * message - but, unlike {@link #group(List)}, deliberately never returns the transcript
     * SourceRecord (attachmentIndex() == 0). Only this message's own newly-arrived attachment
     * records come back, ready for the caller to publish immediately through the existing lane.
     * The transcript itself is intentionally left pending in GroupState (published=false) for
     * the 45-minute inactivity timer (not part of this change - see GroupStateStore.
     * claimForPublication) to publish later.
     * Two different resolution strategies, exactly mirroring {@link #reconcileTimeGap}'s own
     * edit-vs-new split:
     * - an edit/re-read (message.id() already indexed via GroupStateStore.findGroupIdForMessage)
     *   always uses that exact group directly - the current-chat pointer below is never
     *   consulted or updated for this path, so editing an old message can never accidentally
     *   move it into whatever session is currently open for the chat.
     * - a genuinely new message (id not indexed, or no id at all) resolves the chat's currently
     *   open time-gap session via GroupStateStore.resolveOpenChatGroupAndMerge, which runs the
     *   whole "read the chat's pointer, load its candidate GroupState, decide reuse-vs-new,
     *   merge, persist" sequence under ONE chat-level lock (see that method's javadoc for why a
     *   per-group lock alone can't prevent two concurrent genuinely-new messages for the same
     *   chat from minting two different session UUIDs). The candidate is only ever a hint: its
     *   last activity is derived here from its own GroupState.messages() (always kept sorted by
     *   mergeSnapshots, so the last element is the latest timestamp - see that method), never
     *   from anything duplicated in the pointer itself, and a stale/missing candidate simply
     *   falls through to minting a new session, never an error.
     * Callers must only pass a non-thread message (threadId() == null) - a threaded message's
     * transcript is still published immediately today (see class javadoc: the delayed-transcript
     * design is specific to non-thread time-gap sessions), so a real-time threaded message should
     * keep going through {@link #group(List)} unchanged instead of this method.
     */
    public RealtimeReconcileResult reconcileNonThreadedMessage(ChatMessage message) {
        if (message.threadId() != null) {
            throw new IllegalArgumentException(
                    "reconcileNonThreadedMessage only supports non-thread messages, got threadId="
                            + message.threadId());
        }
        RawSourceType sourceType = message.sourceType();
        String tenantId = message.tenantId();
        Optional<GroupState> resolved = message.id() == null
                ? Optional.empty()
                : resolveCurrentGroupId(sourceType, tenantId, message.id());

        if (resolved.isPresent() && isThreadGroup(resolved.get())) {
            // This message's own event carries no threadId (a thread root's event never does -
            // see class javadoc), but it resolves to an existing thread group (a root edit after
            // the thread was created - see resolveCurrentGroupId's javadoc for why the stale
            // messageId index can't be trusted here). Route by the resolved group's kind, not by
            // event shape: thread groups always merge-and-publish immediately.
            String threadGroupId = resolved.get().groupId();
            List<SourceRecord> records = mergeAndMaybePublish(
                    sourceType, tenantId, threadGroupId, MISSING_GROUP_SENTINEL, List.of(message));
            GroupState threadState = groupStateStore.load(sourceType, tenantId, threadGroupId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Thread group vanished immediately after being merged: " + threadGroupId));
            // A root edit must also reach the channel group's own still-living copy of the root
            // (see docs/plans/slack-superseded-fix.md §2) - reuses mergeIntoGroupState, the same
            // primitive the ordinary edit path below uses, so a Slack retry of this same event is
            // just as idempotent here (contentUnchanged branch) as it already is there.
            // confirmedUnchanged is consulted (unlike the thrown-away AtomicBoolean this used to
            // pass) so a message_changed that didn't actually change the root's text (e.g. Slack
            // only bumping reply_count when a reply is added) returns null here instead of the
            // merged-but-identical GroupState - the caller (SlackEventsController) only calls
            // scheduleOrReschedule when this is non-null, and scheduling an already-published,
            // byte-for-byte-unchanged group would arm a pointless correction-delay republish of
            // content that was never actually edited.
            GroupState channelGroupState = findChannelGroupIfDifferentFromThread(
                            sourceType, tenantId, message.id(), threadGroupId)
                    .map(channelGroupId -> {
                        AtomicBoolean confirmedUnchanged = new AtomicBoolean(false);
                        GroupState merged = mergeIntoGroupState(
                                sourceType, tenantId, channelGroupId, MISSING_GROUP_SENTINEL, List.of(message),
                                confirmedUnchanged);
                        return confirmedUnchanged.get() ? null : merged;
                    })
                    .orElse(null);
            return new RealtimeReconcileResult(threadState, records, false, true, channelGroupState);
        }

        GroupState updated = resolved.isPresent()
                ? mergeIntoGroupState(
                        sourceType, tenantId, resolved.get().groupId(), MISSING_GROUP_SENTINEL, List.of(message),
                        new AtomicBoolean())
                : mergeIntoCurrentChatGroup(sourceType, tenantId, message);

        List<SourceRecord> newAttachmentRecords = newlyLandableAttachmentRecords(updated, List.of(message));
        // newActivity is true only for the genuinely-new-message branch, false for an edit/
        // re-read - see RealtimeReconcileResult's javadoc. Scheduling itself no longer branches
        // on this (SlackEventsController schedules whenever groupState().published() is false,
        // covering edits too - an edit can just as well produce a fresh unpublished revision
        // needing a live timer); this flag is kept as it may still be useful metadata for a
        // caller distinguishing "brand new session/message" from "edit of existing content".
        return new RealtimeReconcileResult(updated, newAttachmentRecords, resolved.isEmpty());
    }

    /**
     * Resolves which group a message currently lives in for the purpose of an edit or a
     * deletion, trying a direct load first (succeeds only when messageId is itself a group's own
     * groupId - i.e. a thread's root, since groupId==threadId==root's ts for threaded groups;
     * never a time-gap group, whose groupId is an unrelated minted UUID) before falling back to
     * the messageId index (resolves the ordinary non-thread case, and a reply's id - replies are
     * indexed to their thread group, unlike the root - see GroupStateStore.writeMessageIndex).
     * This order is what fixes the pre-existing thread-root edit bug: GroupStateStore.
     * writeMessageIndex deliberately never indexes a thread group under its own groupId (the
     * root), so findGroupIdForMessage(rootTs) alone permanently resolves to the root's ORIGINAL
     * channel (non-thread) group - see GroupStateStore's javadoc and
     * ChatGroupStateReconciler.newThreadGroupState. The direct load bypasses that stale index
     * entirely whenever a thread group actually exists at that id, so this method alone resolves
     * to exactly the thread group for a single edit/delete lookup; a root edit/delete also
     * needing to reach the channel group's own copy (see §2 of docs/plans/slack-superseded-fix.md)
     * is handled by a second, explicit lookup alongside this one in reconcileNonThreadedMessage/
     * removeMessage, not by widening what this method itself returns.
     * The caller inspects the resolved GroupState's own threadId()/groupId() fields (see
     * isThreadGroup) to decide routing - never which lookup succeeded.
     */
    Optional<GroupState> resolveCurrentGroupId(RawSourceType sourceType, String tenantId, String messageId) {
        Optional<GroupState> direct = groupStateStore.load(sourceType, tenantId, messageId);
        if (direct.isPresent()) {
            return direct;
        }
        return groupStateStore.findGroupIdForMessage(sourceType, tenantId, messageId)
                .flatMap(groupId -> groupStateStore.load(sourceType, tenantId, groupId));
    }

    /** A group is a thread group iff threadId() != null && groupId().equals(threadId()) -
     * true regardless of whether it was reached via resolveCurrentGroupId's direct-load (root)
     * or index-fallback (reply) branch, and false for every ordinary time-gap group (see
     * resolveCurrentGroupId's javadoc). */
    private boolean isThreadGroup(GroupState state) {
        return state.threadId() != null && state.groupId().equals(state.threadId());
    }

    /**
     * Resolves the channel (non-thread) group that still holds messageId's own index entry, if
     * that is a different group than the thread already resolved for it - the same filter idiom
     * migratedRootSnapshot already uses (findGroupIdForMessage(...).filter(id -> !id.equals(...))).
     * Only ever non-empty for messageId == a thread's own root (threadId==groupId==root's id, see
     * GroupState's javadoc): a reply's id is indexed to the thread itself, so this always returns
     * empty for a reply edit/delete. Used by reconcileNonThreadedMessage/removeMessage to also
     * reach the channel group's own still-living copy of the root (see
     * docs/plans/slack-superseded-fix.md §2) alongside resolveCurrentGroupId's single-group
     * resolution, rather than widening what that method itself returns.
     */
    private Optional<String> findChannelGroupIfDifferentFromThread(
            RawSourceType sourceType, String tenantId, String messageId, String threadGroupId) {
        return groupStateStore.findGroupIdForMessage(sourceType, tenantId, messageId)
                .filter(id -> !id.equals(threadGroupId));
    }

    /**
     * New entry point for both message_deleted and a nested-tombstone deletion (see
     * SlackEventMessageParser.parseDeletion): resolves the group via resolveCurrentGroupId, then
     * removes the matching MessageSnapshot under the same per-groupId lock mergeIntoGroupState
     * already uses. Reports which kind of group was resolved (thread vs. non-thread) so the
     * caller (SlackEventsController) can route the result to the correct publish mechanism - a
     * thread group publishes immediately, a non-thread group's removal just advances its
     * revision and leaves the inactivity timer to publish later (see SlackSessionScheduler).
     * An unknown/not-found messageId, or one already removed by an earlier delivery, is a no-op:
     * RemovalResult.removed() is false and nothing is persisted - mirrors the existing
     * "not found" tolerance pattern elsewhere in this class/GroupStateStore (e.g.
     * confirmPublished/claimForPublication).
     * <p>
     * <b>Invariant this method alone is responsible for upholding:</b> a persisted GroupState with
     * revision &gt;= 0 is empty (messages().isEmpty()) ONLY because this method produced it.
     * Every other mutator in this class/GroupStateStore only ever appends or replaces-by-id
     * (mergeSnapshots never removes an entry) - before this method existed, revision &gt;= 0 with
     * an empty messages() was structurally unreachable. If a future change anywhere else ever
     * empties messages() directly, it silently breaks this invariant - which is exactly what lets
     * SlackSessionScheduler/ChatSourceRecordMapper treat "empty + revision &gt;= 0" as unambiguous
     * proof that this deletion feature (and only this feature) produced the state they're looking
     * at. See mergingNeverProducesAnEmptyGroupWithoutGoingThroughRemoveMessage in
     * ChatGroupStateReconcilerTest for the regression test.
     */
    public RemovalResult removeMessage(RawSourceType sourceType, String tenantId, String messageId) {
        Optional<GroupState> resolved = resolveCurrentGroupId(sourceType, tenantId, messageId);
        if (resolved.isEmpty()) {
            return new RemovalResult(null, false, false, null);
        }
        GroupState target = resolved.get();
        AtomicBoolean removed = new AtomicBoolean(false);
        GroupState updated = removeFromGroup(
                target.sourceType(), target.tenantId(), target.groupId(), messageId, removed);

        // A root deletion must also reach the channel group's own still-living copy of the root
        // (see docs/plans/slack-superseded-fix.md §2) - reuses this exact same removeFromGroup
        // primitive, so a Slack retry of this same event is just as idempotent here (the
        // noneMatch no-op branch) as the ordinary single-group removal above already is.
        GroupState channelGroupState = isThreadGroup(updated)
                ? findChannelGroupIfDifferentFromThread(sourceType, tenantId, messageId, updated.groupId())
                        .map(channelGroupId -> removeFromGroup(
                                sourceType, tenantId, channelGroupId, messageId, new AtomicBoolean()))
                        .orElse(null)
                : null;

        return new RemovalResult(updated, removed.get(), isThreadGroup(updated), channelGroupState);
    }

    /** The actual per-group removal mutator shared by {@link #removeMessage}'s thread/channel-
     * group lookups - see that method's Invariant javadoc (an empty, revision &gt;= 0 group is
     * only ever produced here). removed is set (not returned) so a caller doing a second,
     * channel-group removal for the same messageId can track it independently of the first. */
    private GroupState removeFromGroup(
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
                    // Same revision-advance rule as any other content change (mergeIntoGroupState)
                    // - deletion is not a different kind of change, just another input to it.
                    boolean supersedesAnOutstandingVersion = current.published() || current.claimedRevision() != null;
                    int revision = supersedesAnOutstandingVersion ? current.revision() + 1 : Math.max(current.revision(), 0);
                    return new GroupState(
                            current.groupId(), current.sourceType(), current.tenantId(), current.chatId(),
                            current.threadId(), current.correlationId(), remaining, false, revision, null);
                });
    }

    /**
     * Builds the SourceRecord(s) to publish immediately for an already-merged thread group -
     * used by SlackEventsController after {@link #removeMessage} resolves a deletion to a thread
     * group (no new incoming message to report, so newMessagesThisBatch is empty - every
     * attachment this group already has was landed when its owning message first arrived). An
     * empty mergedGroup.messages() (the thread's last message/root was just removed) still
     * produces exactly one record, a DELETE_REVIEW, via ChatSourceRecordMapper's empty-group
     * branch - see that class's javadoc.
     */
    public List<SourceRecord> toSourceRecordsForPublishedGroup(GroupState mergedGroup) {
        return chatSourceRecordMapper.toSourceRecords(mergedGroup, List.of());
    }

    /** Result of {@link #removeMessage}: groupState is the persisted state after the removal
     * attempt (null if resolveCurrentGroupId found nothing at all); removed is false for a
     * no-op (unknown/already-removed messageId, or no group found) - the caller must not treat
     * groupState as having actually changed in that case; isThreadGroup tells the caller which
     * publish mechanism applies (see this class's javadoc). channelGroupState is the channel
     * group's own resulting state when this was a root deletion that also reached its still-
     * living channel-group copy (see docs/plans/slack-superseded-fix.md §2) - null for every
     * ordinary (non-root) deletion, where there is only ever one group. The caller
     * (SlackEventsController) is responsible for scheduling it (sessionScheduler.
     * scheduleOrReschedule) - this class never schedules, only persists. */
    public record RemovalResult(GroupState groupState, boolean removed, boolean isThreadGroup, GroupState channelGroupState) {
    }

    /**
     * The genuinely-new-message branch of {@link #reconcileNonThreadedMessage}: resolves and
     * merges into the chat's currently open non-thread time-gap session, all inside the single
     * chat-level lock GroupStateStore.resolveOpenChatGroupAndMerge holds for the duration of this
     * callback (see its javadoc) - reuseCandidate/mergeIntoGroupState below run entirely inside
     * that lock, so no other genuinely-new message for this same chat can observe a stale
     * candidate or independently mint a competing session UUID for the same window.
     * A candidate is only reusable while OPEN - not published, and not already claimed for
     * publication (see GroupStateStore.claimForPublication). CLAIMED is treated the same as
     * CLOSED here even though the claim itself hasn't been confirmed yet: once a revision is
     * claimed, that GroupState must be frozen against further merges so the in-flight
     * publication (and any future startup-recovery/semantic-processing step reading that frozen
     * snapshot) always sees a stable revision - see the session-lifecycle inspection this fix
     * implements. A genuinely-new message that would otherwise have joined a since-closed/claimed
     * candidate simply starts its own new session instead (own groupId/correlationId, per
     * newGroupState) - it never touches or invalidates the candidate's claim.
     * Deliberately checked here, not inside withinSessionGap: that method stays purely temporal
     * (adjacency in time), while OPEN/CLAIMED/CLOSED is lifecycle state - keeping the two
     * concerns separate rather than folding lifecycle checks into the gap heuristic.
     */
    /** Every new attachment record for incoming, minus whichever ones GroupState already has
     * recorded as landed-to-MinIO for their owning message (see
     * GroupStateStore.recordLandedAttachment/MessageSnapshot.landedAttachments) - this is
     * MinIO-landing dedup only; Kafka publication for the whole group happens once, at session
     * close (see SlackSessionPublisher), not per attachment here. */
    private List<SourceRecord> newlyLandableAttachmentRecords(GroupState state, List<ChatMessage> incoming) {
        return chatSourceRecordMapper.toSourceRecords(state, incoming).stream()
                .filter(record -> record.attachmentIndex() > 0)
                .filter(record -> !alreadyLanded(state, record))
                .toList();
    }

    /** True only for an attachment record (attachmentIndex() > 0, attachmentFileId() != null)
     * whose owning message's MessageSnapshot already lists this exact fileId as landed - see
     * SourceRecord.attachmentFileId's javadoc for why fileId, not attachmentIndex, is the
     * matching key. A record with no fileId (non-Slack attachment, or a Slack source that never
     * populated one) is never treated as already landed - this filter only ever suppresses a
     * re-land it can positively identify as redundant, never guesses. */
    private boolean alreadyLanded(GroupState state, SourceRecord record) {
        if (record.attachmentFileId() == null || record.sourceMessageIds().isEmpty()) {
            return false;
        }
        String messageId = record.sourceMessageIds().get(0);
        return state.messages().stream()
                .filter(snapshot -> snapshot.messageId().equals(messageId))
                .findFirst()
                .map(snapshot -> snapshot.landedAttachments().stream()
                        .anyMatch(la -> la.fileId().equals(record.attachmentFileId())))
                .orElse(false);
    }

    private GroupState mergeIntoCurrentChatGroup(RawSourceType sourceType, String tenantId, ChatMessage message) {
        Duration gap = Duration.ofMinutes(properties.sessionGapMinutes());
        return groupStateStore.resolveOpenChatGroupAndMerge(sourceType, tenantId, message.chatId(), candidate -> {
            boolean reuseCandidate = candidate.isPresent()
                    && isOpenForNewMessages(candidate.get())
                    && withinSessionGap(candidate.get(), message.timestamp(), gap);
            String groupId = reuseCandidate ? candidate.get().groupId() : UUID.randomUUID().toString();
            GroupState emptyValue = reuseCandidate
                    ? MISSING_GROUP_SENTINEL
                    : newGroupState(groupId, sourceType, tenantId, message.chatId(), null);
            return mergeIntoGroupState(sourceType, tenantId, groupId, emptyValue, List.of(message), new AtomicBoolean());
        });
    }

    /** Whether a candidate GroupState is still OPEN for a genuinely-new message to join - false
     * once it's published (CLOSED) or has an outstanding publication claim (CLAIMED/CLOSING),
     * per the session-lifecycle decision above. */
    private boolean isOpenForNewMessages(GroupState candidate) {
        return !candidate.published() && candidate.claimedRevision() == null;
    }

    /**
     * Whether messageTimestamp falls within the configured session gap of candidate's latest
     * persisted activity - derived from GroupState.messages() (kept sorted ascending by
     * mergeSnapshots, so the last element is always the latest), never from any timestamp
     * duplicated in the current-chat pointer itself (see GroupStateStore.OpenChatPointer's
     * javadoc for why that field doesn't exist). Symmetric (abs()), not "must be after", so a
     * late-arriving event can still join a session whose latest recorded activity is actually
     * after it - matching ChatSessionGrouper.partitionByTimeGap's own gap semantics, which are
     * likewise a plain Duration.between(...) magnitude check between adjacent messages.
     */
    private boolean withinSessionGap(GroupState candidate, Instant messageTimestamp, Duration gap) {
        if (candidate.messages().isEmpty()) {
            return false;
        }
        Instant lastActivity = candidate.messages().get(candidate.messages().size() - 1).timestamp();
        return Duration.between(lastActivity, messageTimestamp).abs().compareTo(gap) <= 0;
    }

    /**
     * Result of reconcileNonThreadedMessage: groupState is the persisted state after merging the
     * real-time message in (enough for a caller to inspect e.g. its revision), newAttachmentRecords
     * is what the caller should publish right away - never including the transcript record.
     * newActivity is true only when message was genuinely new (extends/opens the chat's current
     * time-gap session), false for an edit/re-read of an already-indexed message. Not currently
     * consulted to decide scheduling (see SlackEventsController.reconcileAndPublish, which
     * schedules based on groupState().published() instead, since an edit can also produce a
     * fresh unpublished revision that needs a live timer) - kept as descriptive metadata about
     * what kind of message this reconcile call actually processed.
     * threadRouted is true only when this message resolved to an existing thread group despite
     * carrying no threadId of its own (a thread root's edit - see this class's javadoc) - in
     * that case newAttachmentRecords actually holds the FULL record set (transcript included),
     * ready for the caller to publish immediately through the lane, exactly like the ordinary
     * threaded path (group()); false for the normal non-thread case, where it only ever holds
     * newly-landable attachment records and the transcript stays pending for the scheduler.
     * channelGroupState is the channel group's own resulting state when this was a root edit
     * that also reached its still-living channel-group copy (see
     * docs/plans/slack-superseded-fix.md §2) - null for every ordinary (non-root) case, where
     * there is only ever one group. The caller (SlackEventsController) is responsible for
     * scheduling it (sessionScheduler.scheduleOrReschedule) - this class never schedules, only
     * persists.
     */
    public record RealtimeReconcileResult(
            GroupState groupState, List<SourceRecord> newAttachmentRecords, boolean newActivity, boolean threadRouted,
            GroupState channelGroupState) {

        /** Convenience constructor for every existing non-thread-routed call site - there is
         * only ever one group to report for those, so channelGroupState is always null. */
        public RealtimeReconcileResult(GroupState groupState, List<SourceRecord> newAttachmentRecords, boolean newActivity) {
            this(groupState, newAttachmentRecords, newActivity, false, null);
        }
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

    /** Mints a fresh correlationId for a genuinely new group - see GroupState's javadoc for why
     * this only ever happens here (once), never on an update to an already-persisted group.
     * Starts at revision -1 (not yet a real version) so the first merge in mergeAndMaybePublish
     * - a content change from nothing to something, against an unpublished (nonexistent)
     * version - lands at revision 0 via the same unpublished-collapse rule any later pre-publish
     * edit uses (see mergeIntoGroupState), rather than a special-cased starting value. */
    private GroupState newGroupState(
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
     * thread this returns a plain not-actually-used empty value instead (mergeIntoGroupState's
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
    private GroupState newThreadGroupState(
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
