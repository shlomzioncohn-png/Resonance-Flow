package com.rnd.chain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import java.util.UUID;

/**
 * Read-facing representation of a Task returned by graph-traversal endpoints.
 *
 * Readiness-related fields are sourced from task_readiness rather than
 * the legacy counters stored on Task.
 */
public class TaskResponse {

    private final UUID id;
    private final UUID parentTaskId;
    private final TaskStatus status;
    private final boolean isReady;
    private final int blockingCount;
    private final int openConstraintCount;
    private final Integer estimatedDurationMin;
    private final Double score;

    public TaskResponse(
            UUID id,
            UUID parentTaskId,
            TaskStatus status,
            boolean isReady,
            int blockingCount,
            int openConstraintCount,
            Integer estimatedDurationMin,
            Double score) {

        this.id = id;
        this.parentTaskId = parentTaskId;
        this.status = status;
        this.isReady = isReady;
        this.blockingCount = blockingCount;
        this.openConstraintCount = openConstraintCount;
        this.estimatedDurationMin = estimatedDurationMin;
        this.score = score;
    }

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

    public UUID getId() {
        return id;
    }

    public UUID getParentTaskId() {
        return parentTaskId;
    }

    public TaskStatus getStatus() {
        return status;
    }

    @JsonProperty("isReady")
    public boolean isReady() {
        return isReady;
    }

    public int getBlockingCount() {
        return blockingCount;
    }

    public int getOpenConstraintCount() {
        return openConstraintCount;
    }

    public Integer getEstimatedDurationMin() {
        return estimatedDurationMin;
    }

    public Double getScore() {
        return score;
    }
}