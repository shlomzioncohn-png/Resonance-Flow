package com.rnd.chain.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskStateHistoryEntryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private final UUID id = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @Test
    void statusChanged_carriesStatusesAndNoCounts() {
        TaskStateHistoryEntry e = TaskStateHistoryEntry.statusChanged(id, taskId, TaskStatus.PENDING,
                TaskStatus.ACTIVE, NOW);

        assertThat(e.eventType()).isEqualTo(TaskStateEventType.STATUS_CHANGED);
        assertThat(e.fromStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(e.toStatus()).isEqualTo(TaskStatus.ACTIVE);
        assertThat(e.blockingCountBefore()).isNull();
        assertThat(e.blockingCountAfter()).isNull();
    }

    @Test
    void blocked_goesFromZeroToPositive() {
        TaskStateHistoryEntry e = TaskStateHistoryEntry.blocked(id, taskId, 3, NOW);

        assertThat(e.blockingCountBefore()).isZero();
        assertThat(e.blockingCountAfter()).isEqualTo(3);
        assertThat(e.fromStatus()).isNull();
    }

    @Test
    void unblocked_goesFromPositiveToZero() {
        TaskStateHistoryEntry e = TaskStateHistoryEntry.unblocked(id, taskId, 2, NOW);

        assertThat(e.blockingCountBefore()).isEqualTo(2);
        assertThat(e.blockingCountAfter()).isZero();
    }

    @Test
    void statusChanged_rejectsMissingStatusOrCounts() {
        assertThatThrownBy(() -> TaskStateHistoryEntry.statusChanged(id, taskId, null, TaskStatus.ACTIVE, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TaskStateHistoryEntry(id, taskId, TaskStateEventType.STATUS_CHANGED,
                TaskStatus.PENDING, TaskStatus.ACTIVE, 0, 1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blockedAndUnblocked_rejectWrongCountsOrStatuses() {
        assertThatThrownBy(() -> TaskStateHistoryEntry.blocked(id, taskId, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskStateHistoryEntry.unblocked(id, taskId, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskStateHistoryEntry(id, taskId, TaskStateEventType.BLOCKED,
                TaskStatus.PENDING, null, 0, 1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskStateHistoryEntry(id, taskId, TaskStateEventType.UNBLOCKED,
                null, null, 1, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresIdTaskIdAndCreatedAt() {
        assertThatThrownBy(() -> TaskStateHistoryEntry.blocked(null, taskId, 1, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TaskStateHistoryEntry.blocked(id, null, 1, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TaskStateHistoryEntry.blocked(id, taskId, 1, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equality_isByIdAlone() {
        TaskStateHistoryEntry a = TaskStateHistoryEntry.blocked(id, taskId, 1, NOW);
        TaskStateHistoryEntry b = TaskStateHistoryEntry.unblocked(id, UUID.randomUUID(), 4, NOW.plusSeconds(1));

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(TaskStateHistoryEntry.blocked(UUID.randomUUID(), taskId, 1, NOW));
    }
}
