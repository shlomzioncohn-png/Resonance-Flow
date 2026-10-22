package com.rnd.chain.service;

import com.rnd.chain.dto.TaskStateHistoryEntry;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.TaskStateHistoryRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Appends rows to the task state history (issue #774). Callers invoke it only after the change it
 * describes was actually written, inside the same transaction, so a failure here rolls the
 * change back too. Deliberately independent of the stored-counter maintenance in
 * {@link DependencyEdgeService}, which is slated for removal once the counters become computed.
 */
@Component
public class TaskStateHistoryRecorder {

    private final TaskStateHistoryRepository taskStateHistoryRepository;
    private final Clock clock;

    public TaskStateHistoryRecorder(TaskStateHistoryRepository taskStateHistoryRepository, Clock clock) {
        this.taskStateHistoryRepository = Objects.requireNonNull(taskStateHistoryRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Appends STATUS_CHANGED; a no-op when the status did not change. */
    @Transactional
    public void recordStatusChange(UUID taskId, TaskStatus from, TaskStatus to) {
        if (from == to) {
            return;
        }
        taskStateHistoryRepository.record(TaskStateHistoryEntry.statusChanged(
                UUID.randomUUID(), taskId, from, to, Instant.now(clock)));
    }

    /** Appends BLOCKED (0 to positive) or UNBLOCKED (positive to 0); any other change records nothing. */
    @Transactional
    public void recordBlockingTransition(UUID taskId, int before, int after) {
        if (before == 0 && after > 0) {
            taskStateHistoryRepository.record(TaskStateHistoryEntry.blocked(
                    UUID.randomUUID(), taskId, after, Instant.now(clock)));
        } else if (before > 0 && after == 0) {
            taskStateHistoryRepository.record(TaskStateHistoryEntry.unblocked(
                    UUID.randomUUID(), taskId, before, Instant.now(clock)));
        }
    }
}
