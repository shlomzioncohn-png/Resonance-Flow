package com.rnd.bridge.intake.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.lane.GroupStateStore;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.storage.ControlPlaneStateStores;
import com.rnd.bridge.storage.InMemoryObjectStorageClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Each test simulates one or more separate intake runs by calling reconciler.group() more than
 * once against the same GroupStateStore/InMemoryObjectStorageClient instance — that's the
 * cross-run persistence this class exists for (threaded continuation, and edits/re-reads by
 * messageId regardless of grouping strategy), so it wouldn't be meaningfully tested any other
 * way (a fresh reconciler per call would hide exactly the behavior under test). Time-gap
 * *grouping* of genuinely new messages is deliberately batch-local only — see
 * eachIntakeRunStartsItsOwnTimeGapGroupsEvenWithinTheGapThreshold.
 * Reconciliation alone never marks a group published — that only happens once
 * UnstructuredIntakeLane's afterPublish hook calls GroupStateStore.confirmPublished after a
 * real Kafka publish succeeds (see ChatGroupPublishConfirmationTest for that end-to-end path).
 * Tests here that need an "already published" precondition call confirmPublished directly to
 * simulate a prior run's publish having actually succeeded.
 */
class ChatGroupStateReconcilerTest {

    // Shared so ObjectStorageClient (reads/index) and ControlPlaneStateStore (the group
    // record's atomic write) see the same data - in production both hit the same MinIO bucket.
    private final Map<String, byte[]> backingStore = new ConcurrentHashMap<>();
    private final InMemoryObjectStorageClient storageClient = new InMemoryObjectStorageClient(backingStore);
    private final GroupStateStore groupStateStore = new GroupStateStore(
            storageClient, ControlPlaneStateStores.inMemory(backingStore),
            new ObjectMapper().registerModule(new JavaTimeModule()));
    private final ChatSessionProperties properties = new ChatSessionProperties(45);
    private final ChatSessionGrouper chatSessionGrouper = new ChatSessionGrouper(properties);
    private final ChatSourceRecordMapper chatSourceRecordMapper = new ChatSourceRecordMapper();
    private final ChatGroupStateReconciler reconciler =
            new ChatGroupStateReconciler(properties, chatSessionGrouper, chatSourceRecordMapper, groupStateStore);

    private ChatMessage message(String chatId, String sender, String isoTimestamp, String text, String id) {
        return new ChatMessage(
                RawSourceType.SLACK, "kamatech", chatId, sender, Instant.parse(isoTimestamp), text, List.of(), id, null);
    }

    private ChatMessage threadMessage(
            String chatId, String sender, String isoTimestamp, String text, String id, String threadId) {
        return new ChatMessage(
                RawSourceType.SLACK, "kamatech", chatId, sender, Instant.parse(isoTimestamp), text, List.of(), id, threadId);
    }

    @Test
    void newTimeGapGroupIsCreatedAsPendingAtRevisionZero() {
        List<SourceRecord> records = reconciler.group(List.of(
                message("chat-a", "ruth", "2026-08-18T09:00:00Z", "hi", "m1"),
                message("chat-a", "malki", "2026-08-18T09:10:00Z", "hey", "m2")));

        assertThat(records).hasSize(1);
        SourceRecord record = records.get(0);
        assertThat(record.body()).contains("ruth: hi").contains("malki: hey");
        assertThat(record.sourceMessageIds()).containsExactly("m1", "m2");

        // record.correlationId() is Mind's UUID, not GroupStateStore's own key - look the group
        // up the same way UnstructuredIntakeLane.afterPublish does, via the messageId index.
        String groupId = groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1").orElseThrow();
        GroupState state = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(state.correlationId()).isEqualTo(record.correlationId());
        assertThat(state.revision()).isZero();
        // Reconciliation never marks a group published itself - only a confirmed Kafka
        // publish does (see UnstructuredIntakeLane.afterPublish).
        assertThat(state.published()).isFalse();
    }

    @Test
    void newGroupGetsAUuidCorrelationId() {
        List<SourceRecord> records = reconciler.group(List.of(
                message("chat-a", "ruth", "2026-08-18T09:00:00Z", "hi", "m1")));

        String correlationId = records.get(0).correlationId();
        assertThat(UUID.fromString(correlationId)).isNotNull(); // throws IllegalArgumentException if not a UUID
    }

    @Test
    void correlationIdIsNeverTheInternalThreadIdOrSourceMessageId() {
        List<SourceRecord> records = reconciler.group(List.of(
                threadMessage("chat-a", "malki", "2026-08-18T09:00:00Z", "root question", "t1", "t1")));

        String correlationId = records.get(0).correlationId();
        assertThat(correlationId).isNotEqualTo("t1");
        assertThat(records.get(0).sourceMessageIds()).doesNotContain(correlationId);
    }

    @Test
    void withinBatchTimeGapSplittingStillCreatesSeparateGroups() {
        List<SourceRecord> records = reconciler.group(List.of(
                message("chat-a", "ruth", "2026-08-18T09:00:00Z", "morning session", "m1"),
                message("chat-a", "ruth", "2026-08-18T11:00:00Z", "afternoon session", "m2"))); // 2h gap, same batch

        assertThat(records).hasSize(2);
        assertThat(records.get(0).correlationId()).isNotEqualTo(records.get(1).correlationId());
        assertThat(records.get(0).body()).isEqualTo("ruth: morning session");
        assertThat(records.get(1).body()).isEqualTo("ruth: afternoon session");
    }

    @Test
    void eachIntakeRunStartsItsOwnTimeGapGroupsEvenWithinTheGapThreshold() {
        List<SourceRecord> run1 = reconciler.group(List.of(
                message("chat-a", "ruth", "2026-08-18T10:00:00Z", "hi", "m1"),
                message("chat-a", "malki", "2026-08-18T10:10:00Z", "hey", "m2")));
        String firstCorrelationId = run1.get(0).correlationId();
        String firstGroupId = groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1").orElseThrow();
        groupStateStore.confirmPublished(RawSourceType.SLACK, "kamatech", firstGroupId, 0);

        // Only 5 minutes after the last message - well within the 45-minute gap threshold -
        // but time-gap grouping is deliberately batch-local only now, so a second run's
        // genuinely new message always starts its own group instead of reconnecting.
        List<SourceRecord> run2 = reconciler.group(List.of(
                message("chat-a", "ruth", "2026-08-18T10:15:00Z", "still there?", "m3")));

        assertThat(run2).hasSize(1);
        assertThat(run2.get(0).correlationId()).isNotEqualTo(firstCorrelationId);
        assertThat(run2.get(0).body()).isEqualTo("ruth: still there?");

        // The original group is untouched by the new one being created.
        GroupState firstState = groupStateStore.load(RawSourceType.SLACK, "kamatech", firstGroupId).orElseThrow();
        assertThat(firstState.correlationId()).isEqualTo(firstCorrelationId);
        assertThat(firstState.messages()).hasSize(2);
        assertThat(firstState.revision()).isZero();
        assertThat(firstState.published()).isTrue();
    }

    @Test
    void threadContinuationByThreadIdExtendsTheSameGroupAndBumpsRevision() {
        List<SourceRecord> run1 = reconciler.group(List.of(
                threadMessage("chat-a", "malki", "2026-08-18T09:00:00Z", "root question", "t1", "t1")));
        String correlationId = run1.get(0).correlationId();
        groupStateStore.confirmPublished(RawSourceType.SLACK, "kamatech", "t1", 0);

        List<SourceRecord> run2 = reconciler.group(List.of(
                // 5 days later - thread continuation ignores the time gap entirely.
                threadMessage("chat-a", "ruth", "2026-08-23T09:00:00Z", "late reply", "t2", "t1")));

        assertThat(run2).hasSize(1);
        // Same logical group (same thread), so Mind must see the exact same correlationId again.
        assertThat(run2.get(0).correlationId()).isEqualTo(correlationId);
        assertThat(run2.get(0).body()).contains("root question").contains("late reply");

        GroupState state = groupStateStore.load(RawSourceType.SLACK, "kamatech", "t1").orElseThrow();
        assertThat(state.revision()).isEqualTo(1);
        assertThat(state.correlationId()).isEqualTo(correlationId);
    }

    @Test
    void existingMessageIdIsMatchedToItsGroupRatherThanCreatingANewGroup() {
        List<SourceRecord> run1 = reconciler.group(List.of(
                message("chat-a", "ruth", "2026-08-18T09:00:00Z", "hi", "m1")));
        String correlationId = run1.get(0).correlationId();
        String groupId = groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1").orElseThrow();

        List<SourceRecord> run2 = reconciler.group(List.of(
                message("chat-a", "ruth", "2026-08-18T09:00:00Z", "hi (edited)", "m1")));

        assertThat(run2).hasSize(1);
        // Same logical group being edited, so Mind must see the exact same correlationId again.
        assertThat(run2.get(0).correlationId()).isEqualTo(correlationId);
        assertThat(groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1")).contains(groupId);
    }

    @Test
    void changingAnExistingPublishedGroupsContentIncrementsRevisionAndRebuildsTheFullTranscript() {
        reconciler.group(List.of(message("chat-a", "ruth", "2026-08-18T09:00:00Z", "hi", "m1")));
        String groupId = groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1").orElseThrow();
        groupStateStore.confirmPublished(RawSourceType.SLACK, "kamatech", groupId, 0);

        List<SourceRecord> run2 = reconciler.group(List.of(
                message("chat-a", "ruth", "2026-08-18T09:00:00Z", "hi (edited)", "m1")));

        GroupState state = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(state.revision()).isEqualTo(1);
        assertThat(state.messages().get(0).text()).isEqualTo("hi (edited)");
        assertThat(run2.get(0).body()).isEqualTo("ruth: hi (edited)");
    }

    @Test
    void anEditArrivingBeforeThePreviousAckGetsAHigherRevision() {
        reconciler.group(List.of(message("chat-a", "ruth", "2026-08-18T09:00:00Z", "hi", "m1")));
        String groupId = groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1").orElseThrow();
        // No confirmPublished call - the revision 0 candidate's Kafka ack has not come back yet.
        assertThat(groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow().published())
                .isFalse();

        reconciler.group(List.of(message("chat-a", "ruth", "2026-08-18T09:00:00Z", "hi (edited)", "m1")));

        GroupState state = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        // A genuine content change is a new logical version regardless of whether the version
        // it replaced was ever confirmed - it must not keep sharing revision 0's number.
        assertThat(state.revision()).isEqualTo(1);
        assertThat(state.messages().get(0).text()).isEqualTo("hi (edited)");
        assertThat(state.published()).isFalse();
    }

    @Test
    void theStaleAckForThePreEditRevisionCannotMarkTheEditedRevisionPublished() {
        reconciler.group(List.of(message("chat-a", "ruth", "2026-08-18T09:00:00Z", "hi", "m1")));
        String groupId = groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1").orElseThrow();

        // The edit lands before revision 0's ack comes back, advancing to revision 1 (still
        // unconfirmed) - see anEditArrivingBeforeThePreviousAckGetsAHigherRevision.
        reconciler.group(List.of(message("chat-a", "ruth", "2026-08-18T09:00:00Z", "hi (edited)", "m1")));
        assertThat(groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow().revision())
                .isEqualTo(1);

        // The stale ack for revision 0 - the pre-edit candidate - finally arrives.
        groupStateStore.confirmPublished(RawSourceType.SLACK, "kamatech", groupId, 0);

        GroupState state = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(state.revision()).isEqualTo(1);
        // The edited content (revision 1) was never actually confirmed by Kafka - a stale ack
        // for the superseded revision 0 must not mark it published.
        assertThat(state.published()).isFalse();
    }

    @Test
    void unchangedReReadOfAConfirmedGroupDoesNotIncrementRevisionOrRepublish() {
        reconciler.group(List.of(message("chat-a", "ruth", "2026-08-18T09:00:00Z", "hi", "m1")));
        String groupId = groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1").orElseThrow();
        groupStateStore.confirmPublished(RawSourceType.SLACK, "kamatech", groupId, 0);

        List<SourceRecord> run2 = reconciler.group(List.of(
                message("chat-a", "ruth", "2026-08-18T09:00:00Z", "hi", "m1"))); // identical re-read

        assertThat(run2).isEmpty();
        GroupState state = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(state.revision()).isZero();
        assertThat(state.published()).isTrue();
    }

    @Test
    void unchangedReReadOfAnUnconfirmedGroupStillRetriesWithoutBumpingRevision() {
        List<SourceRecord> run1 = reconciler.group(List.of(message("chat-a", "ruth", "2026-08-18T09:00:00Z", "hi", "m1")));
        // No confirmPublished call - simulates a run whose Kafka publish never got confirmed
        // (e.g. it failed, or the app crashed before the ack came back).
        String groupId = groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1").orElseThrow();
        assertThat(groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow().published()).isFalse();

        List<SourceRecord> run2 = reconciler.group(List.of(
                message("chat-a", "ruth", "2026-08-18T09:00:00Z", "hi", "m1"))); // identical re-read

        assertThat(run2).hasSize(1); // must retry, not skip
        // A retry of the same group must keep the same correlationId, not mint a new one.
        assertThat(run2.get(0).correlationId()).isEqualTo(run1.get(0).correlationId());
        GroupState state = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(state.revision()).isZero(); // a retry is not a content change
        assertThat(state.published()).isFalse();
    }

    @Test
    void unchangedReReadOfAConfirmedGroupStillRegeneratesAttachmentsOnly() {
        ChatMessage.Attachment attachment =
                new ChatMessage.Attachment("note.txt", "text/plain", "fake-bytes".getBytes());
        ChatMessage withAttachment = new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "ruth", Instant.parse("2026-08-18T09:00:00Z"),
                "listen to this", List.of(attachment), "m1", null);

        List<SourceRecord> run1 = reconciler.group(List.of(withAttachment));
        assertThat(run1).hasSize(2); // transcript + attachment
        String correlationId = run1.get(0).correlationId();
        // Transcript and attachment belong to the same logical group/revision, so Mind must
        // see them share the exact same correlationId (see ChatSessionGrouper.toSourceRecords).
        assertThat(run1.get(1).correlationId()).isEqualTo(correlationId);
        String groupId = groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1").orElseThrow();
        groupStateStore.confirmPublished(RawSourceType.SLACK, "kamatech", groupId, 0);

        List<SourceRecord> run2 = reconciler.group(List.of(withAttachment)); // identical re-read

        // The transcript is a confirmed no-op, but the attachment has no confirmation state of
        // its own, so it must still be regenerated (see mergeAndMaybePublish).
        assertThat(run2).hasSize(1);
        assertThat(run2.get(0).attachmentIndex()).isGreaterThan(0);
        // The regenerated attachment retry still carries the group's stable correlationId.
        assertThat(run2.get(0).correlationId()).isEqualTo(correlationId);
        GroupState state = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(state.revision()).isZero();
    }

    @Test
    void messageOrderingIsPreservedAfterReconciliationEvenWhenALateArrivingMessageHasAnEarlierTimestamp() {
        // Thread continuation, not time-gap: threadId-based grouping is the one strategy that
        // still legitimately spans separate intake runs (see class javadoc).
        reconciler.group(List.of(
                threadMessage("chat-a", "ruth", "2026-08-18T09:00:00Z", "first", "m1", "t1"),
                threadMessage("chat-a", "malki", "2026-08-18T09:10:00Z", "third", "m3", "t1")));

        // m2's source timestamp falls between m1 and m3, but it only arrives in this later run.
        List<SourceRecord> run2 = reconciler.group(List.of(
                threadMessage("chat-a", "ruth", "2026-08-18T09:05:00Z", "second", "m2", "t1")));

        assertThat(run2).hasSize(1);
        String body = run2.get(0).body();
        assertThat(body.indexOf("first")).isLessThan(body.indexOf("second"));
        assertThat(body.indexOf("second")).isLessThan(body.indexOf("third"));
        assertThat(run2.get(0).sourceMessageIds()).containsExactly("m1", "m2", "m3");
    }

    /**
     * Regression test for the load-then-save race this GroupStateStore.update() refactor
     * closes: before it, reconcileThreaded/reconcileTimeGap loaded `existing` themselves and
     * passed it into mergeAndMaybePublish, so two concurrent reconciler.group() calls touching
     * the same thread could both load the same stale GroupState, merge in different messages,
     * and have the second save() silently overwrite the first's. Every caller here targets the
     * same threadId ("t1"), so they all contend on the exact same GroupStateStore.update() key -
     * the scenario that used to be lossy.
     */
    @Test
    void concurrentUpdatesToTheSameGroupDoNotLoseMessages() throws InterruptedException {
        int callers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch done = new CountDownLatch(callers);

        for (int i = 0; i < callers; i++) {
            int callerId = i;
            pool.submit(() -> {
                try {
                    reconciler.group(List.of(threadMessage(
                            "chat-a", "ruth", "2026-08-18T09:%02d:00Z".formatted(callerId),
                            "message " + callerId, "m" + callerId, "t1")));
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        GroupState state = groupStateStore.load(RawSourceType.SLACK, "kamatech", "t1").orElseThrow();
        List<String> messageIds = state.messages().stream().map(MessageSnapshot::messageId).toList();
        assertThat(messageIds).hasSize(callers);
        for (int i = 0; i < callers; i++) {
            assertThat(messageIds).contains("m" + i);
        }
    }
}
