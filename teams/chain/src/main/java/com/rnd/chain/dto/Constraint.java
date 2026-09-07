package com.rnd.chain.dto;

import java.time.LocalDateTime;
import java.util.Collection;
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
        this.constraintId = constraintId;
        this.taskId = taskId;
        this.type = type;
        this.status = status;
        // Defensive copy, matching Task.copyOf's philosophy of never handing out (or storing) a
        // reference the caller can use to mutate this object's state from the outside.
        this.reason = copyReason(reason);
        this.sourceRef = sourceRef;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getConstraintId() { return constraintId; }
    public UUID getTaskId() { return taskId; }
    public ConstraintType getType() { return type; }
    public ConstraintStatus getStatus() { return status; }

    public Map<String, Object> getReason() { return copyReason(reason); }

    public String getSourceRef() { return sourceRef; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setStatus(ConstraintStatus status) { this.status = status; }
    public void setReason(Map<String, Object> reason) { this.reason = copyReason(reason); }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    // Only copies the top-level map, not its values (review finding #4) - a value that is
    // itself a Collection or Map would still be a live, externally-mutable reference even
    // after this "defensive" copy. Rather than a full recursive deep-copy (impractical to do
    // generically for an unstructured Map<String, Object>), this rejects that shape outright:
    // reason is documented/expected to hold flat scalar values (strings, numbers, dates), so a
    // nested Collection/Map is refused rather than silently allowing a copy that isn't actually
    // safe.
    private static Map<String, Object> copyReason(Map<String, Object> reason) {
        if (reason == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : reason.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Collection || value instanceof Map) {
                throw new IllegalArgumentException(
                    "Constraint reason value for key '" + entry.getKey() + "' must not be a "
                        + "Collection or Map - reason only supports flat scalar values, since "
                        + "the defensive copy does not recurse into nested mutable structures");
            }
        }
        return Collections.unmodifiableMap(new HashMap<>(reason));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Constraint)) {
            return false;
        }
        Constraint other = (Constraint) o;
        return Objects.equals(constraintId, other.constraintId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(constraintId);
    }

    public enum ConstraintType { APPROVAL_NEEDED, BLOCKED_BY_INFO, DATE_WINDOW }
    public enum ConstraintStatus { PENDING, SATISFIED, WAIVED }
}
