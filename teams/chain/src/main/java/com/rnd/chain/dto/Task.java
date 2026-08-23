package com.rnd.chain.dto;

import java.time.LocalDateTime;

// TODO: import the Inbox type once the Inbox team publishes its package,
// e.g. import com.inbox.Inbox;

public class Task {

    // Identifier type follows Inbox: we store the task id exactly as Inbox sends it
    // and do not convert it on our side, so the id format stays owned by Inbox.
    private Inbox.TaskId id;
    private Inbox.TaskId parentTaskId;

    private TaskStatus status;
    private boolean isReady;
    private int blockingCount;
    private int openConstraintCount;

    private Integer estimatedDurationMin;
    private Integer score;

    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public Task(Inbox.TaskId id,
                Inbox.TaskId parentTaskId,
                TaskStatus status,
                boolean isReady,
                int blockingCount,
                int openConstraintCount,
                Integer estimatedDurationMin,
                Integer score,
                LocalDateTime createdAt,
                LocalDateTime updatedAt,
                LocalDateTime deletedAt) {
        this.id = id;
        this.parentTaskId = parentTaskId;
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

    public Inbox.TaskId getId() {
        return id;
    }

    public void setId(Inbox.TaskId id) {
        this.id = id;
    }

    public Inbox.TaskId getParentTaskId() {
        return parentTaskId;
    }

    public void setParentTaskId(Inbox.TaskId parentTaskId) {
        this.parentTaskId = parentTaskId;
    }

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

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

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
