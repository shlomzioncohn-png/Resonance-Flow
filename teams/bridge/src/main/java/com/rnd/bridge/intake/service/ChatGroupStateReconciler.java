package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.lane.GroupStateStore;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.RawSourceType;
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
            new GroupState(null, null, null, null, null, null, List.of(), false, -1);

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
     *   further, but attachments carry no confirmation state of their own, so they're still
     *   regenerated from this batch's messages (see toSourceRecords) - otherwise an attachment
     *   that failed to publish independently of an already-confirmed transcript would never
     *   be retried, silently dropped by the transcript's own no-op. Revision untouched.
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

        GroupState updated = groupStateStore.update(sourceType, tenantId, groupId, emptyValue, current -> {
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
            return new GroupState(
                    current.groupId(), current.sourceType(), current.tenantId(), current.chatId(),
                    current.threadId(), current.correlationId(), merged, false, revision);
        });

        if (confirmedUnchanged.get()) {
            return chatSourceRecordMapper.toSourceRecords(updated, incoming).stream()
                    .filter(record -> record.attachmentIndex() > 0)
                    .toList();
        }
        return chatSourceRecordMapper.toSourceRecords(updated, incoming);
    }

    /** Merge by messageId (same id = edit, replace text; new id = append), then re-sort by
     * source timestamp so the rebuilt transcript stays in the order messages actually occurred. */
    private List<MessageSnapshot> mergeSnapshots(List<MessageSnapshot> existing, List<ChatMessage> incoming) {
        Map<String, MessageSnapshot> byId = new LinkedHashMap<>();
        for (MessageSnapshot snapshot : existing) {
            byId.put(snapshot.messageId(), snapshot);
        }
        for (ChatMessage message : incoming) {
            byId.put(message.id(), new MessageSnapshot(
                    message.id(), message.sender(), message.timestamp(), message.text(), message.attachments().size()));
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
                groupId, sourceType, tenantId, chatId, threadId, UUID.randomUUID().toString(), List.of(), false, -1);
    }
}
