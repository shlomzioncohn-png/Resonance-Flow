package com.rnd.chain.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, protocol-agnostic record of one task status change or blocking transition.
 * Only the fields meaningful for {@code eventType} are set - the rest are null, mirroring the
 * {@code chk_task_state_history_shape} CHECK (V9 migration):
 * STATUS_CHANGED carries from/to status; BLOCKED (0 to positive) and UNBLOCKED (positive to 0)
 * carry blocking_count before/after.
 * Identity (equals/hashCode) is by {@code id} alone, same as {@link DependencyHistoryEntry}.
 */
public record TaskStateHistoryEntry(UUID id,
                                    UUID taskId,
                                    TaskStateEventType eventType,
                                    TaskStatus fromStatus,
                                    TaskStatus toStatus,
                                    Integer blockingCountBefore,
                                    Integer blockingCountAfter,
                                    Instant createdAt) {

    public TaskStateHistoryEntry {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        switch (eventType) {
            case STATUS_CHANGED -> {
                Objects.requireNonNull(fromStatus, "fromStatus must not be null for STATUS_CHANGED");
                Objects.requireNonNull(toStatus, "toStatus must not be null for STATUS_CHANGED");
                requireNoCounts(eventType, blockingCountBefore, blockingCountAfter);
            }
            case BLOCKED -> {
                requireNoStatuses(eventType, fromStatus, toStatus);
                requireCounts(eventType, blockingCountBefore, blockingCountAfter);
                if (blockingCountBefore != 0 || blockingCountAfter <= 0) {
                    throw new IllegalArgumentException(
                            "BLOCKED requires blockingCountBefore == 0 and blockingCountAfter > 0");
                }
            }
            case UNBLOCKED -> {
                requireNoStatuses(eventType, fromStatus, toStatus);
                requireCounts(eventType, blockingCountBefore, blockingCountAfter);
                if (blockingCountBefore <= 0 || blockingCountAfter != 0) {
                    throw new IllegalArgumentException(
                            "UNBLOCKED requires blockingCountBefore > 0 and blockingCountAfter == 0");
                }
            }
        }
    }

    private static void requireNoCounts(TaskStateEventType type, Integer before, Integer after) {
        if (before != null || after != null) {
            throw new IllegalArgumentException(type + " must not carry blocking counts");
        }
    }

    private static void requireNoStatuses(TaskStateEventType type, TaskStatus from, TaskStatus to) {
        if (from != null || to != null) {
            throw new IllegalArgumentException(type + " must not carry from/to status");
        }
    }

    private static void requireCounts(TaskStateEventType type, Integer before, Integer after) {
        if (before == null || after == null) {
            throw new IllegalArgumentException(type + " requires blockingCountBefore and blockingCountAfter");
        }
    }

    public static TaskStateHistoryEntry statusChanged(UUID id, UUID taskId, TaskStatus from, TaskStatus to,
                                                      Instant createdAt) {
        return new TaskStateHistoryEntry(id, taskId, TaskStateEventType.STATUS_CHANGED, from, to, null, null,
                createdAt);
    }

    public static TaskStateHistoryEntry blocked(UUID id, UUID taskId, int blockingCountAfter, Instant createdAt) {
        return new TaskStateHistoryEntry(id, taskId, TaskStateEventType.BLOCKED, null, null, 0, blockingCountAfter,
                createdAt);
    }

    public static TaskStateHistoryEntry unblocked(UUID id, UUID taskId, int blockingCountBefore, Instant createdAt) {
        return new TaskStateHistoryEntry(id, taskId, TaskStateEventType.UNBLOCKED, null, null, blockingCountBefore, 0,
                createdAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaskStateHistoryEntry other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
