package com.rnd.chain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;
import java.util.UUID;

/**
 * Read-facing representation of a Task returned by graph-traversal endpoints.
 *
 * Readiness-related fields are sourced from task_readiness rather than
 * the legacy counters stored on Task.
 */
@Getter
@AllArgsConstructor
public class TaskResponse {

    private final UUID id;
    private final UUID parentTaskId;
    private final TaskStatus status;

    @Getter(onMethod_ = @JsonProperty("isReady"))
    private final boolean isReady;

    private final int blockingCount;
    private final int openConstraintCount;
    private final Integer estimatedDurationMin;
    private final Double score;

    /**
     * Builds an API response from the task domain data plus the authoritative
     * readiness state from task_readiness.
     */
    public static TaskResponse from(
            Task task,
            TaskReadinessSnapshot readiness) {

        Objects.requireNonNull(
                task,
                "task must not be null");

        Objects.requireNonNull(
                readiness,
                "readiness must not be null");

        if (!task.getId().equals(readiness.taskId())) {
            throw new IllegalArgumentException(
                    "Task/readiness id mismatch: task="
                            + task.getId()
                            + ", readiness="
                            + readiness.taskId());
        }

        return new TaskResponse(
                task.getId(),
                task.getParentTaskId(),
                task.getStatus(),
                readiness.isReady(),
                readiness.blockingCount(),
                readiness.openConstraintCount(),
                task.getEstimatedDurationMin(),
                task.getScore());
    }
}