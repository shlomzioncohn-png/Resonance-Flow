package com.rnd.bridge.intake.lane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.storage.ControlPlaneStateStores;
import com.rnd.bridge.storage.InMemoryObjectStorageClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
            new GroupState("g1", RawSourceType.SLACK, "kamatech", "chat-a", null, "c1", List.of(), false, 0);

    @Test
    void loadReturnsEmptyWhenNothingSavedYet() {
        assertThat(store.load(RawSourceType.SLACK, "kamatech", "g1")).isEmpty();
        assertThat(store.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1")).isEmpty();
    }

    @Test
    void saveThenLoadRoundTripsTheGroup() {
        GroupState state = new GroupState(
                "g1", RawSourceType.SLACK, "kamatech", "chat-a", null, "c1",
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi")),
                true, 0);

        store.save(state);

        assertThat(store.load(RawSourceType.SLACK, "kamatech", "g1")).contains(state);
    }

    @Test
    void saveIndexesEveryMessageIdToTheGroup() {
        GroupState state = new GroupState(
                "g1", RawSourceType.SLACK, "kamatech", "chat-a", null, "c1",
                List.of(
                        new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi"),
                        new MessageSnapshot("m2", "malki", Instant.parse("2026-08-18T09:01:00Z"), "hey")),
                true, 0);

        store.save(state);

        assertThat(store.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1")).contains("g1");
        assertThat(store.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m2")).contains("g1");
    }

    @Test
    void updateStartsFromEmptyValueWhenNothingPersistedYet() {
        GroupState result = store.update(RawSourceType.SLACK, "kamatech", "g1", EMPTY, current -> new GroupState(
                current.groupId(), current.sourceType(), current.tenantId(), current.chatId(), current.threadId(),
                current.correlationId(),
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi")),
                false, 0));

        assertThat(result.messages()).hasSize(1);
        assertThat(store.load(RawSourceType.SLACK, "kamatech", "g1")).contains(result);
    }

    @Test
    void updateMutatesWhateverWasPreviouslyPersisted() {
        store.update(RawSourceType.SLACK, "kamatech", "g1", EMPTY, current -> new GroupState(
                current.groupId(), current.sourceType(), current.tenantId(), current.chatId(), current.threadId(),
                current.correlationId(),
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi")),
                false, 0));

        GroupState result = store.update(RawSourceType.SLACK, "kamatech", "g1", EMPTY, current -> new GroupState(
                current.groupId(), current.sourceType(), current.tenantId(), current.chatId(), current.threadId(),
                current.correlationId(),
                List.of(
                        current.messages().get(0),
                        new MessageSnapshot("m2", "malki", Instant.parse("2026-08-18T09:01:00Z"), "hey")),
                false, 0));

        assertThat(result.messages()).extracting(MessageSnapshot::messageId).containsExactly("m1", "m2");
    }

    @Test
    void updateRefreshesTheMessageIndexToMatchTheReturnedState() {
        store.update(RawSourceType.SLACK, "kamatech", "g1", EMPTY, current -> new GroupState(
                current.groupId(), current.sourceType(), current.tenantId(), current.chatId(), current.threadId(),
                current.correlationId(),
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi")),
                false, 0));

        assertThat(store.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1")).contains("g1");
    }

    @Test
    void confirmPublishedFlipsPublishedWhenTheRevisionStillMatches() {
        store.update(RawSourceType.SLACK, "kamatech", "g1", EMPTY, current -> new GroupState(
                current.groupId(), current.sourceType(), current.tenantId(), current.chatId(), current.threadId(),
                current.correlationId(),
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi")),
                false, 0));

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
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi")),
                false, 0));

        // 2. the group is updated to revision 1 before the ack for revision 0 arrives.
        store.update(RawSourceType.SLACK, "kamatech", "g1", EMPTY, current -> new GroupState(
                current.groupId(), current.sourceType(), current.tenantId(), current.chatId(), current.threadId(),
                current.correlationId(),
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi (edited)")),
                false, 1));

        // 3. the stale ack for revision 0 arrives.
        store.confirmPublished(RawSourceType.SLACK, "kamatech", "g1", 0);

        // 4. revision 1 must remain published=false.
        GroupState state = store.load(RawSourceType.SLACK, "kamatech", "g1").orElseThrow();
        assertThat(state.revision()).isEqualTo(1);
        assertThat(state.published()).isFalse();
    }
}
