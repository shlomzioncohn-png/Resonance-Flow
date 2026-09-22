package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.lane.GroupStateStore;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.GroupState;
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
            GroupState emptyValue = newGroupState(groupId, first.sourceType(), first.tenantId(), first.chatId(), groupId);
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
     * - unchanged content, already confirmed published: the transcript itself needs nothing
     *   further, but each attachment DOES carry its own independent confirmation state (see
     *   MessageSnapshot.confirmedAttachmentFileIds/GroupStateStore.confirmAttachmentPublished),
     *   so newlyConfirmableAttachmentRecords still regenerates candidates from this batch's
     *   messages and filters out whichever ones are already confirmed - an attachment that
     *   failed to publish independently of an already-confirmed transcript is still retried,
     *   but one that already succeeded is not silently republished by the transcript's own
     *   no-op. Revision untouched.
     * - unchanged content, not yet confirmed: a retry, not a content change - rebuild and
     *   republish (transcript included) at the same revision.
     * - changed content: always advances the revision, whether or not the version being
     *   replaced had already been confirmed published - an edit arriving while the previous
     *   candidate is still in flight (unconfirmed) is still a genuinely new version, and must
     *   not share its revision number with that still-unconfirmed candidate. Sharing it would
     *   let a later stale ACK for the old candidate incorrectly confirm the edited content
     *   instead (see ChatGroupStateReconcilerTest.anEditArrivingBeforeThePreviousAckGetsAHigherRevision
     *   and its stale-ACK sibling).
     */
    private List<SourceRecord> mergeAndMaybePublish(
            RawSourceType sourceType, String tenantId, String groupId, GroupState emptyValue,
            List<ChatMessage> incoming) {
        AtomicBoolean confirmedUnchanged = new AtomicBoolean(false);
        GroupState updated = mergeIntoGroupState(sourceType, tenantId, groupId, emptyValue, incoming, confirmedUnchanged);

        if (confirmedUnchanged.get()) {
            return newlyConfirmableAttachmentRecords(updated, incoming);
        }
        return chatSourceRecordMapper.toSourceRecords(updated, incoming).stream()
                .filter(record -> record.attachmentIndex() == 0 || !alreadyConfirmed(updated, record))
                .toList();
    }

    /** Every attachment record for incoming, minus whichever ones GroupState already has
     * recorded as confirmed-published for their owning message (see
     * MessageSnapshot.confirmedAttachmentFileIds) - the transcript record is never present here
     * (attachmentIndex() == 0 is filtered out by toSourceRecords' caller contract in this
     * branch), so unlike the sibling filter above there is nothing else to keep unconditionally. */
    private List<SourceRecord> newlyConfirmableAttachmentRecords(GroupState state, List<ChatMessage> incoming) {
        return chatSourceRecordMapper.toSourceRecords(state, incoming).stream()
                .filter(record -> record.attachmentIndex() > 0)
                .filter(record -> !alreadyConfirmed(state, record))
                .toList();
    }

    /** True only for an attachment record (attachmentIndex() > 0, attachmentFileId() != null)
     * whose owning message's MessageSnapshot already lists this exact fileId as confirmed - see
     * SourceRecord.attachmentFileId's javadoc for why fileId, not attachmentIndex, is the
     * matching key. A record with no fileId (non-Slack attachment, or a Slack source that never
     * populated one) is never treated as already confirmed - this filter only ever suppresses a
     * republish it can positively identify as redundant, never guesses. */
    private boolean alreadyConfirmed(GroupState state, SourceRecord record) {
        if (record.attachmentFileId() == null || record.sourceMessageIds().isEmpty()) {
            return false;
        }
        String messageId = record.sourceMessageIds().get(0);
        return state.messages().stream()
                .filter(snapshot -> snapshot.messageId().equals(messageId))
                .findFirst()
                .map(snapshot -> snapshot.confirmedAttachmentFileIds().contains(record.attachmentFileId()))
                .orElse(false);
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
            int revision = contentUnchanged ? current.revision() : current.revision() + 1;
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
        Optional<String> existingGroupId = message.id() == null
                ? Optional.empty()
                : groupStateStore.findGroupIdForMessage(sourceType, tenantId, message.id());

        GroupState updated = existingGroupId.isPresent()
                ? mergeIntoGroupState(
                        sourceType, tenantId, existingGroupId.get(), MISSING_GROUP_SENTINEL, List.of(message),
                        new AtomicBoolean())
                : mergeIntoCurrentChatGroup(sourceType, tenantId, message);

        List<SourceRecord> newAttachmentRecords = newlyConfirmableAttachmentRecords(updated, List.of(message));
        // newActivity is true only for the genuinely-new-message branch, false for an edit/
        // re-read - see RealtimeReconcileResult's javadoc. Scheduling itself no longer branches
        // on this (SlackEventsController schedules whenever groupState().published() is false,
        // covering edits too - an edit can just as well produce a fresh unpublished revision
        // needing a live timer); this flag is kept as it may still be useful metadata for a
        // caller distinguishing "brand new session/message" from "edit of existing content".
        return new RealtimeReconcileResult(updated, newAttachmentRecords, existingGroupId.isEmpty());
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
     */
    public record RealtimeReconcileResult(GroupState groupState, List<SourceRecord> newAttachmentRecords, boolean newActivity) {
    }

    /** Merge by messageId (same id = edit, replace text; new id = append), then re-sort by
     * source timestamp so the rebuilt transcript stays in the order messages actually occurred.
     * A replaced snapshot carries forward whichever of its previously confirmedAttachmentFileIds
     * are still among the incoming message's current attachments (see
     * MessageSnapshot.confirmedAttachmentFileIds) - a Slack retry or a text-only edit of an
     * already-published attachment must not forget it was already published, but a fileId that's
     * no longer attached (removed by the edit) is simply dropped rather than kept as stale state
     * for an attachment that no longer exists on this message. */
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
            List<String> carriedConfirmedFileIds = previous == null
                    ? List.of()
                    : previous.confirmedAttachmentFileIds().stream().filter(currentFileIds::contains).toList();
            byId.put(message.id(), new MessageSnapshot(
                    message.id(), message.sender(), message.timestamp(), message.text(),
                    message.attachments().size(), carriedConfirmedFileIds));
        }
        return byId.values().stream()
                .sorted((a, b) -> a.timestamp().compareTo(b.timestamp()))
                .toList();
    }

    /** Mints a fresh correlationId for a genuinely new group - see GroupState's javadoc for why
     * this only ever happens here (once), never on an update to an already-persisted group.
     * Starts at revision -1 (not yet a real version) so the first merge in mergeAndMaybePublish
     * - a content change from nothing to something - advances it to revision 0, the same path
     * every later edit takes rather than a special-cased starting value. */
    private GroupState newGroupState(
            String groupId, RawSourceType sourceType, String tenantId, String chatId, String threadId) {
        return new GroupState(
                groupId, sourceType, tenantId, chatId, threadId, UUID.randomUUID().toString(), List.of(), false, -1,
                null);
    }
}
