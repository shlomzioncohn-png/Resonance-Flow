package com.rnd.chain.dto;

import java.util.UUID;

/**
 * Read-facing view of {@link Task} returned by every graph-traversal endpoint. Mirrors the
 * TaskResponse shape already established (unused so far) on the Riki-Kaplan branch - same field
 * list, same purpose (don't leak the domain object directly into API responses) - adapted only
 * where that branch's own field list disagrees with the real {@link Task}, now sourced verbatim
 * from chain/ruthHoltzman/dependency-repository-66 (immutable, no setters): here score is a
 * {@code Double}, not an {@code Integer}, and there is no ownerId field on this response,
 * matching the Riki-Kaplan branch precedent's field list rather than adding a field it didn't
 * have.
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

    public TaskResponse(UUID id,
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

    public static TaskResponse from(Task task) {
        return new TaskResponse(
                task.getId(),
                task.getParentTaskId(),
                task.getStatus(),
                task.isReady(),
                task.getBlockingCount(),
                task.getOpenConstraintCount(),
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
