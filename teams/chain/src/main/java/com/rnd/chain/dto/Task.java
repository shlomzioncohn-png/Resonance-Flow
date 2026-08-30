package com.rnd.chain.dto;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

// Identifier type: UUID, pending Inbox publishing its own task id type.
public class Task {

    private final UUID id;
    private UUID parentTaskId;
    private String ownerId;

    private TaskStatus status;

    /** Whether all of this task's dependencies and constraints are satisfied, so it can be worked on. */
    private boolean isReady;

    /** Number of not-yet-completed tasks that this task depends on (i.e. tasks blocking it). */
    private int blockingCount;

    /** Number of unresolved constraints (e.g. scheduling/resource constraints) still open on this task. */
    private int openConstraintCount;

    private Integer estimatedDurationMin;
    private Double score;

    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    private Task(UUID id,
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
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (ownerId == null) {
            throw new IllegalArgumentException("ownerId must not be null");
        }
        if (blockingCount < 0) {
            throw new IllegalArgumentException("blockingCount must not be negative");
        }
        if (openConstraintCount < 0) {
            throw new IllegalArgumentException("openConstraintCount must not be negative");
        }
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Task)) {
            return false;
        }
        return id.equals(((Task) o).id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return an independent copy of this task, with the same field
     * values. Used by repositories to hand out/store snapshots instead
     * of shared references, so mutating one copy never affects another.
     */
    public Task copy() {
        return new Task(id, parentTaskId, ownerId, status, isReady, blockingCount,
                openConstraintCount, estimatedDurationMin, score, createdAt, updatedAt, deletedAt);
    }

    /** Builder for {@link Task}, useful given the number of constructor parameters. */
    public static class Builder {
        private UUID id;
        private UUID parentTaskId;
        private String ownerId;
        private TaskStatus status;
        private boolean isReady;
        private int blockingCount;
        private int openConstraintCount;
        private Integer estimatedDurationMin;
        private Double score;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private LocalDateTime deletedAt;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder parentTaskId(UUID parentTaskId) {
            this.parentTaskId = parentTaskId;
            return this;
        }

        public Builder ownerId(String ownerId) {
            this.ownerId = ownerId;
            return this;
        }

        public Builder status(TaskStatus status) {
            this.status = status;
            return this;
        }

        public Builder isReady(boolean isReady) {
            this.isReady = isReady;
            return this;
        }

        public Builder blockingCount(int blockingCount) {
            this.blockingCount = blockingCount;
            return this;
        }

        public Builder openConstraintCount(int openConstraintCount) {
            this.openConstraintCount = openConstraintCount;
            return this;
        }

        public Builder estimatedDurationMin(Integer estimatedDurationMin) {
            this.estimatedDurationMin = estimatedDurationMin;
            return this;
        }

        public Builder score(Double score) {
            this.score = score;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public Builder deletedAt(LocalDateTime deletedAt) {
            this.deletedAt = deletedAt;
            return this;
        }

        public Task build() {
            return new Task(id, parentTaskId, ownerId, status, isReady, blockingCount,
                    openConstraintCount, estimatedDurationMin, score, createdAt, updatedAt, deletedAt);
        }
    }
}
