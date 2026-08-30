package com.rnd.chain.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public class Constraint {

    private final String constraintId;
    private final UUID taskId;
    private final ConstraintType type;
    private ConstraintStatus status;
    private Map<String, Object> reason;
    private final String sourceRef;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Constraint(
            String constraintId,
            UUID taskId,
            ConstraintType type,
            ConstraintStatus status,
            Map<String, Object> reason,
            String sourceRef,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this.constraintId = constraintId;
        this.taskId = taskId;
        this.type = type;
        this.status = status;
        this.reason = reason;
        this.sourceRef = sourceRef;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getConstraintId() {
        return constraintId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public ConstraintType getType() {
        return type;
    }

    public ConstraintStatus getStatus() {
        return status;
    }

    public Map<String, Object> getReason() {
        return reason;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setStatus(ConstraintStatus status) {
        this.status = status;
    }

    public void setReason(Map<String, Object> reason) {
        this.reason = reason;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public enum ConstraintType {
        APPROVAL_NEEDED,
        BLOCKED_BY_INFO,
        DATE_WINDOW
    }

    public enum ConstraintStatus {
        PENDING,
        SATISFIED,
        WAIVED
    }
}