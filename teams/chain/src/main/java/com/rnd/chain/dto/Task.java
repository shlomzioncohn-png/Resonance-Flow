package com.rnd.chain.dto;

import java.time.LocalDateTime;
import java.util.UUID;

// Identifier type: UUID, pending Inbox publishing its own task id type.
public class Task {

    private UUID id;
    private UUID parentTaskId;
    private String ownerId;

    private TaskStatus status;
    private boolean isReady;
    private int blockingCount;
    private int openConstraintCount;

    private Integer estimatedDurationMin;
    private Double score;

    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public Task(UUID id,
                UUID parentTaskId,
                String ownerId,
                TaskStatus status,
                boolean isReady,
                int blockingCount,
                int openConstraintCount,
                Integer estimatedDurationMin,
                Double score,
                LocalDateTime createdAt,
                LocalDateTime updatedAt,
                LocalDateTime deletedAt) {
        this.id = id;
        this.parentTaskId = parentTaskId;
        this.ownerId = ownerId;
        this.status = status;
        this.isReady = isReady;
        this.blockingCount = blockingCount;
        this.openConstraintCount = openConstraintCount;
        this.estimatedDurationMin = estimatedDurationMin;
        this.score = score;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getParentTaskId() {
        return parentTaskId;
    }

    public void setParentTaskId(UUID parentTaskId) {
        this.parentTaskId = parentTaskId;
    }

    public String getOwnerId() {return ownerId;}

    public void setOwnerId(String ownerId) {this.ownerId = ownerId;}

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public boolean isReady() {
        return isReady;
    }

    public void setReady(boolean isReady) {
        this.isReady = isReady;
    }

    public int getBlockingCount() {
        return blockingCount;
    }

    public void setBlockingCount(int blockingCount) {
        this.blockingCount = blockingCount;
    }

    public int getOpenConstraintCount() {
        return openConstraintCount;
    }

    public void setOpenConstraintCount(int openConstraintCount) {
        this.openConstraintCount = openConstraintCount;
    }

    public Integer getEstimatedDurationMin() {
        return estimatedDurationMin;
    }

    public void setEstimatedDurationMin(Integer estimatedDurationMin) {
        this.estimatedDurationMin = estimatedDurationMin;
    }

    public Double getScore() {return score;}

    public void setScore(Double score) {this.score = score;}

    // createdAt is final: getter only, no setter
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
