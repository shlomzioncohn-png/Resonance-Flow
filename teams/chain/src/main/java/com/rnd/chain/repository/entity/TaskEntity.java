package com.rnd.chain.repository.entity;

import com.rnd.chain.dto.TaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;
import java.util.UUID;

/** JPA mapping for {@code task} (in the dedicated {@code chain} database - see PR #417).
 * Mapped to/from {@link com.rnd.chain.dto.Task} entirely
 * inside {@link com.rnd.chain.repository.PostgresTaskRepository} - never crosses that boundary. */
@Entity
@Table(name = "task")
public class TaskEntity {

    @Id
    private UUID id;

    @Column(name = "parent_task_id")
    private UUID parentTaskId;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    @Column(name = "is_ready", nullable = false)
    private boolean isReady;

    @Column(name = "blocking_count", nullable = false)
    private int blockingCount;

    @Column(name = "open_constraint_count", nullable = false)
    private int openConstraintCount;

    @Column(name = "estimated_duration_min")
    private Integer estimatedDurationMin;

    private Double score;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    protected TaskEntity() {
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

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
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

    public void setReady(boolean ready) {
        isReady = ready;
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

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public long getVersion() {
        return version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
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
