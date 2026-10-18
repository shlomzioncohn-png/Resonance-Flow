package com.rnd.bridge.intake.lane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
import com.rnd.bridge.storage.ControlPlaneStateStores;
import com.rnd.bridge.storage.InMemoryObjectStorageClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GroupStateStoreTest {

    // Shared so ObjectStorageClient (reads/index) and ControlPlaneStateStore (the group
    // record's atomic write) see the same data - in production both hit the same MinIO bucket.
    private final Map<String, byte[]> backingStore = new ConcurrentHashMap<>();
    private final InMemoryObjectStorageClient storageClient = new InMemoryObjectStorageClient(backingStore);
    private final GroupStateStore store = new GroupStateStore(
            storageClient, ControlPlaneStateStores.inMemory(backingStore),
            new ObjectMapper().registerModule(new JavaTimeModule()));

    private static final GroupState EMPTY =
            new GroupState("g1", RawSourceType.SLACK, "kamatech", "chat-a", null, "c1", List.of(), false, 0, null);

    @Test
    void loadReturnsEmptyWhenNothingSavedYet() {
        assertThat(store.load(RawSourceType.SLACK, "kamatech", "g1")).isEmpty();
        assertThat(store.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1")).isEmpty();
    }

    @Test
    void saveThenLoadRoundTripsTheGroup() {
        GroupState state = new GroupState(
                "g1", RawSourceType.SLACK, "kamatech", "chat-a", null, "c1",
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi", 0)),
                true, 0, null);

        store.save(state);

        assertThat(store.load(RawSourceType.SLACK, "kamatech", "g1")).contains(state);
    }

    @Test
    void saveIndexesEveryMessageIdToTheGroup() {
        GroupState state = new GroupState(
                "g1", RawSourceType.SLACK, "kamatech", "chat-a", null, "c1",
                List.of(
                        new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi", 0),
                        new MessageSnapshot("m2", "malki", Instant.parse("2026-08-18T09:01:00Z"), "hey", 0)),
                true, 0, null);

        store.save(state);

        assertThat(store.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1")).contains("g1");
        assertThat(store.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m2")).contains("g1");
    }

    /**
     * For a threaded group, groupId always equals threadId, which always equals the thread
     * root's own messageId (see GroupState's javadoc). A thread group must never claim index
     * ownership of that messageId - it stays whatever it already was (unset here, but see
     * ChatGroupStateReconciler.newThreadGroupState for the live root -> first-reply case where
     * it's the root's ORIGINAL non-thread group that must keep owning it).
     */
    @Test
    void saveNeverIndexesAMessageWhoseIdEqualsItsOwnGroupId() {
        GroupState threadGroup = new GroupState(
                "r1", RawSourceType.SLACK, "kamatech", "chat-a", "r1", "c1",
                List.of(
                        new MessageSnapshot("r1", "malki", Instant.parse("2026-08-18T09:00:00Z"), "root", 0),
                        new MessageSnapshot("reply1", "ruth", Instant.parse("2026-08-18T09:05:00Z"), "reply", 0)),
                true, 0, null);

        store.save(threadGroup);

        assertThat(store.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "r1")).isEmpty();
        assertThat(store.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "reply1")).contains("r1");
    }

    @Test
    void updateStartsFromEmptyValueWhenNothingPersistedYet() {
        GroupState result = store.update(RawSourceType.SLACK, "kamatech", "g1", EMPTY, current -> new GroupState(
                current.groupId(), current.sourceType(), current.tenantId(), current.chatId(), current.threadId(),
                current.correlationId(),
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi", 0)),
                false, 0, null));

        assertThat(result.messages()).hasSize(1);
        assertThat(store.load(RawSourceType.SLACK, "kamatech", "g1")).contains(result);
    }

    @Test
    void updateMutatesWhateverWasPreviouslyPersisted() {
        store.update(RawSourceType.SLACK, "kamatech", "g1", EMPTY, current -> new GroupState(
                current.groupId(), current.sourceType(), current.tenantId(), current.chatId(), current.threadId(),
                current.correlationId(),
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi", 0)),
                false, 0, null));

        GroupState result = store.update(RawSourceType.SLACK, "kamatech", "g1", EMPTY, current -> new GroupState(
                current.groupId(), current.sourceType(), current.tenantId(), current.chatId(), current.threadId(),
                current.correlationId(),
                List.of(
                        current.messages().get(0),
                        new MessageSnapshot("m2", "malki", Instant.parse("2026-08-18T09:01:00Z"), "hey", 0)),
                false, 0, null));

        assertThat(result.messages()).extracting(MessageSnapshot::messageId).containsExactly("m1", "m2");
    }

    @Test
    void updateRefreshesTheMessageIndexToMatchTheReturnedState() {
        store.update(RawSourceType.SLACK, "kamatech", "g1", EMPTY, current -> new GroupState(
                current.groupId(), current.sourceType(), current.tenantId(), current.chatId(), current.threadId(),
                current.correlationId(),
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi", 0)),
                false, 0, null));

        assertThat(store.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1")).contains("g1");
    }

    @Test
    void confirmPublishedFlipsPublishedWhenTheRevisionStillMatches() {
        store.update(RawSourceType.SLACK, "kamatech", "g1", EMPTY, current -> new GroupState(
                current.groupId(), current.sourceType(), current.tenantId(), current.chatId(), current.threadId(),
                current.correlationId(),
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi", 0)),
                false, 0, null));

        store.confirmPublished(RawSourceType.SLACK, "kamatech", "g1", 0);

        GroupState state = store.load(RawSourceType.SLACK, "kamatech", "g1").orElseThrow();
        assertThat(state.published()).isTrue();
        assertThat(state.revision()).isZero();
    }

    @Test
    void confirmPublishedIsANoOpWhenTheGroupDoesNotExist() {
        store.confirmPublished(RawSourceType.SLACK, "kamatech", "does-not-exist", 0);

        assertThat(store.load(RawSourceType.SLACK, "kamatech", "does-not-exist")).isEmpty();
    }

    /**
     * The scenario this guards against:
     * 1. revision 0 starts publishing - persisted as pending (published=false).
     * 2. the group is updated to revision 1 before the ack for revision 0 arrives (e.g. a
     *    concurrent mergeAndMaybePublish landed first).
     * 3. the (stale) ack for revision 0 arrives and confirmPublished(..., 0) is called.
     * 4. revision 1 must remain published=false - the ack belongs to revision 0's content,
     *    which is no longer the group's current content, so confirming it now would
     *    incorrectly mark revision 1 as published when nothing has actually confirmed it yet.
     */
    @Test
    void confirmPublishedIgnoresAStaleAckForARevisionThatHasSinceBeenSuperseded() {
        // 1. revision 0 starts publishing.
        store.update(RawSourceType.SLACK, "kamatech", "g1", EMPTY, current -> new GroupState(
                current.groupId(), current.sourceType(), current.tenantId(), current.chatId(), current.threadId(),
                current.correlationId(),
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi", 0)),
                false, 0, null));

        // 2. the group is updated to revision 1 before the ack for revision 0 arrives.
        store.update(RawSourceType.SLACK, "kamatech", "g1", EMPTY, current -> new GroupState(
                current.groupId(), current.sourceType(), current.tenantId(), current.chatId(), current.threadId(),
                current.correlationId(),
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi (edited)", 0)),
                false, 1, null));

        // 3. the stale ack for revision 0 arrives.
        store.confirmPublished(RawSourceType.SLACK, "kamatech", "g1", 0);

        // 4. revision 1 must remain published=false.
        GroupState state = store.load(RawSourceType.SLACK, "kamatech", "g1").orElseThrow();
        assertThat(state.revision()).isEqualTo(1);
        assertThat(state.published()).isFalse();
    }

    private void putGroupAtRevision(int revision, boolean published) {
        store.update(RawSourceType.SLACK, "kamatech", "g1", EMPTY, current -> new GroupState(
                current.groupId(), current.sourceType(), current.tenantId(), current.chatId(), current.threadId(),
                current.correlationId(),
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi", 0)),
                published, revision, null));
    }

    @Test
    void claimForPublicationSucceedsForAnUnpublishedCurrentRevision() {
        putGroupAtRevision(0, false);

        boolean claimed = store.claimForPublication(RawSourceType.SLACK, "kamatech", "g1", 0);

        assertThat(claimed).isTrue();
    }

    @Test
    void claimForPublicationCannotClaimTheSameRevisionTwice() {
        putGroupAtRevision(0, false);

        boolean firstClaim = store.claimForPublication(RawSourceType.SLACK, "kamatech", "g1", 0);
        boolean secondClaim = store.claimForPublication(RawSourceType.SLACK, "kamatech", "g1", 0);

        assertThat(firstClaim).isTrue();
        assertThat(secondClaim).isFalse();
    }

    @Test
    void claimForPublicationCannotClaimAnAlreadyPublishedRevision() {
        putGroupAtRevision(0, true);

        boolean claimed = store.claimForPublication(RawSourceType.SLACK, "kamatech", "g1", 0);

        assertThat(claimed).isFalse();
    }

    @Test
    void claimForPublicationCannotClaimAStaleExpectedRevision() {
        putGroupAtRevision(1, false);

        boolean claimed = store.claimForPublication(RawSourceType.SLACK, "kamatech", "g1", 0);

        assertThat(claimed).isFalse();
    }

    @Test
    void claimForPublicationPersistsTheClaim() {
        putGroupAtRevision(0, false);

        store.claimForPublication(RawSourceType.SLACK, "kamatech", "g1", 0);

        GroupState state = store.load(RawSourceType.SLACK, "kamatech", "g1").orElseThrow();
        assertThat(state.claimedRevision()).isEqualTo(0);
    }

    @Test
    void confirmPublishedWorksForTheClaimedExpectedRevision() {
        putGroupAtRevision(0, false);
        store.claimForPublication(RawSourceType.SLACK, "kamatech", "g1", 0);

        store.confirmPublished(RawSourceType.SLACK, "kamatech", "g1", 0);

        GroupState state = store.load(RawSourceType.SLACK, "kamatech", "g1").orElseThrow();
        assertThat(state.published()).isTrue();
    }

    @Test
    void staleConfirmCannotMarkANewerRevisionPublished() {
        putGroupAtRevision(0, false);
        store.claimForPublication(RawSourceType.SLACK, "kamatech", "g1", 0);
        // revision moves on before the (stale) confirm for revision 0 arrives.
        putGroupAtRevision(1, false);

        store.confirmPublished(RawSourceType.SLACK, "kamatech", "g1", 0);

        GroupState state = store.load(RawSourceType.SLACK, "kamatech", "g1").orElseThrow();
        assertThat(state.revision()).isEqualTo(1);
        assertThat(state.published()).isFalse();
    }

    private static final RawObjectRef DUMMY_REF = new RawObjectRef(
            Role.ATTACHMENT, "SLACK/kamatech/2026-08-18/x.txt", "sha256:" + "a".repeat(64), "text/plain", 10L,
            Modality.TEXT);

    @Test
    void recordLandedAttachmentRecordsTheFileIdOnThatMessagesSnapshot() {
        store.update(RawSourceType.SLACK, "kamatech", "g1", EMPTY, current -> new GroupState(
                current.groupId(), current.sourceType(), current.tenantId(), current.chatId(), current.threadId(),
                current.correlationId(),
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi", 1)),
                false, 0, null));

        store.recordLandedAttachment(RawSourceType.SLACK, "kamatech", "g1", "m1", "F1", DUMMY_REF);

        GroupState state = store.load(RawSourceType.SLACK, "kamatech", "g1").orElseThrow();
        assertThat(state.messages().get(0).landedAttachments()).extracting("fileId").containsExactly("F1");
    }

    @Test
    void recordLandedAttachmentAccumulatesMultipleFileIdsOnTheSameMessage() {
        store.update(RawSourceType.SLACK, "kamatech", "g1", EMPTY, current -> new GroupState(
                current.groupId(), current.sourceType(), current.tenantId(), current.chatId(), current.threadId(),
                current.correlationId(),
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi", 2)),
                false, 0, null));

        store.recordLandedAttachment(RawSourceType.SLACK, "kamatech", "g1", "m1", "F1", DUMMY_REF);
        store.recordLandedAttachment(RawSourceType.SLACK, "kamatech", "g1", "m1", "F2", DUMMY_REF);

        GroupState state = store.load(RawSourceType.SLACK, "kamatech", "g1").orElseThrow();
        assertThat(state.messages().get(0).landedAttachments()).extracting("fileId")
                .containsExactlyInAnyOrder("F1", "F2");
    }

    @Test
    void recordLandedAttachmentIsANoOpWhenTheGroupDoesNotExist() {
        store.recordLandedAttachment(RawSourceType.SLACK, "kamatech", "does-not-exist", "m1", "F1", DUMMY_REF);

        assertThat(store.load(RawSourceType.SLACK, "kamatech", "does-not-exist")).isEmpty();
    }

    @Test
    void recordLandedAttachmentIsANoOpWhenTheMessageIsNotInTheGroup() {
        store.update(RawSourceType.SLACK, "kamatech", "g1", EMPTY, current -> new GroupState(
                current.groupId(), current.sourceType(), current.tenantId(), current.chatId(), current.threadId(),
                current.correlationId(),
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi", 0)),
                false, 0, null));

        store.recordLandedAttachment(RawSourceType.SLACK, "kamatech", "g1", "does-not-exist", "F1", DUMMY_REF);

        GroupState state = store.load(RawSourceType.SLACK, "kamatech", "g1").orElseThrow();
        assertThat(state.messages().get(0).landedAttachments()).isEmpty();
    }

    @Test
    void concurrentClaimAttemptsResultInOnlyOneSuccessfulClaim() throws InterruptedException {
        putGroupAtRevision(0, false);
        int attempts = 20;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();

        try {
            for (int i = 0; i < attempts; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (store.claimForPublication(RawSourceType.SLACK, "kamatech", "g1", 0)) {
                        successCount.incrementAndGet();
                    }
                });
            }
            ready.await();
            start.countDown();
        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(successCount.get()).isEqualTo(1);
    }
}
