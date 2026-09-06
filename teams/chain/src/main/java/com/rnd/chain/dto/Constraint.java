package com.rnd.chain.dto;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class Constraint {
    private final String constraintId;
    private final UUID taskId; // pending Inbox publishing its own task id type
    private final ConstraintType type;
    private ConstraintStatus status;
    private Map<String, Object> reason; // may be null
    private final String sourceRef;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Constraint(String constraintId, UUID taskId, ConstraintType type,
                       ConstraintStatus status, Map<String, Object> reason,
                       String sourceRef, LocalDateTime createdAt, LocalDateTime updatedAt) {
        if (constraintId == null) {
            throw new IllegalArgumentException("constraintId must not be null");
        }
        if (taskId == null) {
            throw new IllegalArgumentException("taskId must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        this.constraintId = constraintId;
        this.taskId = taskId;
        this.type = type;
        this.status = status;
        // Defensive copy: reason may be null (no reason recorded), but if a caller passes a
        // mutable Map, we must not hand out a live reference to it - see getReason()'s Javadoc
        // and the code review finding this addresses ("hands out its mutable reason field by
        // reference... a caller can mutate Constraint's internal state without going through
        // any setter").
        this.reason = reason == null ? null : Collections.unmodifiableMap(new HashMap<>(reason));
        this.sourceRef = sourceRef;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getConstraintId() { return constraintId; }
    public UUID getTaskId() { return taskId; }
    public ConstraintType getType() { return type; }
    public ConstraintStatus getStatus() { return status; }
    /** Returns an unmodifiable view - see the defensive copy taken in the constructor and made
     * in {@link #setReason}; callers cannot mutate this Constraint's state through it. */
    public Map<String, Object> getReason() { return reason; }
    public String getSourceRef() { return sourceRef; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setStatus(ConstraintStatus status) { this.status = status; }
    public void setReason(Map<String, Object> reason) {
        this.reason = reason == null ? null : Collections.unmodifiableMap(new HashMap<>(reason));
    }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    // Identity is constraintId alone, matching Task/TaskDependency's equals()/hashCode() pattern
    // elsewhere in this PR - see code review finding ("Constraint breaks the equals/hashCode +
    // encapsulation pattern this PR establishes everywhere else").
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Constraint)) {
            return false;
        }
        return constraintId.equals(((Constraint) o).constraintId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(constraintId);
    }

    public enum ConstraintType { APPROVAL_NEEDED, BLOCKED_BY_INFO, DATE_WINDOW }
    public enum ConstraintStatus { PENDING, SATISFIED, WAIVED }
}
