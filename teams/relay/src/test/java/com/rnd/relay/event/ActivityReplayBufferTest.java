package com.rnd.relay.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityReplayBufferTest {

    private static final String ORG_ID = "org-1";
    private static final String USER_ID = "user-1";

    private static final Instant T0 = Instant.parse("2026-08-30T09:00:00Z");

    private final ActivityReplayBuffer buffer = new ActivityReplayBuffer(200);

    @Test
    void unknownUserIsAMiss() {
        assertThat(buffer.replaySince(ORG_ID, USER_ID, 5)).isEmpty();
    }

    @Test
    void gapCoveredEntirelyByTheBufferIsAHit() {
        buffer.record(ORG_ID, USER_ID, notification("t1"), 10, T0);
        buffer.record(ORG_ID, USER_ID, notification("t2"), 11, T0);
        buffer.record(ORG_ID, USER_ID, notification("t3"), 12, T0);

        Optional<List<ActivityReplayBuffer.Entry>> replay = buffer.replaySince(ORG_ID, USER_ID, 10);

        assertThat(replay).isPresent();
        assertThat(replay.get().stream().map(ActivityReplayBuffer.Entry::offset).toList()).containsExactly(11L, 12L);
    }

    @Test
    void clientAlreadyCaughtUpIsAHitWithNothingToReplay() {
        buffer.record(ORG_ID, USER_ID, notification("t1"), 10, T0);

        Optional<List<ActivityReplayBuffer.Entry>> replay = buffer.replaySince(ORG_ID, USER_ID, 10);

        assertThat(replay).contains(List.of());
    }

    @Test
    void gapOlderThanTheOldestRetainedEntryIsAMiss() {
        for (long offset = 100; offset < 100 + 5; offset++) {
            buffer.record(ORG_ID, USER_ID, notification("t" + offset), offset, T0);
        }

        // asks for everything after offset 50, but the buffer's oldest entry is 100 — a hole
        assertThat(buffer.replaySince(ORG_ID, USER_ID, 50)).isEmpty();
    }

    @Test
    void evictsOldestEntriesPastCapacity() {
        for (long offset = 0; offset < 250; offset++) {
            buffer.record(ORG_ID, USER_ID, notification("t" + offset), offset, T0);
        }

        // capacity is 200, so entries 0..49 are gone; the oldest retained is offset 50
        assertThat(buffer.replaySince(ORG_ID, USER_ID, 49).get()).hasSize(200);
        assertThat(buffer.replaySince(ORG_ID, USER_ID, 48)).isEmpty();
    }

    @Test
    void sinceReturnsOnlyEntriesRecordedAfterTheGivenInstant() {
        buffer.record(ORG_ID, USER_ID, notification("t1"), 10, T0);
        buffer.record(ORG_ID, USER_ID, notification("t2"), 11, T0.plusSeconds(5));
        buffer.record(ORG_ID, USER_ID, notification("t3"), 12, T0.plusSeconds(10));

        List<ActivityReplayBuffer.Entry> result = buffer.since(ORG_ID, USER_ID, T0.plusSeconds(5));

        assertThat(result.stream().map(ActivityReplayBuffer.Entry::offset).toList()).containsExactly(12L);
    }

    @Test
    void sinceForUnknownUserIsEmpty() {
        assertThat(buffer.since(ORG_ID, USER_ID, T0)).isEmpty();
    }

    private static TaskNotification notification(String taskId) {
        return TaskNotification.newBuilder()
                .setEventId(taskId)
                .setOrgId(ORG_ID)
                .setUserId(USER_ID)
                .setTaskId(taskId)
                .setNotificationType(NotificationType.TASK_STATUS_CHANGED)
                .setPushPolicy(PushPolicy.ACTIVITY)
                .build();
    }
}
