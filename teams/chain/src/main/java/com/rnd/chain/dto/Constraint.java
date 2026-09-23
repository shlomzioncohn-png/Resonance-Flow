package com.rnd.chain.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

// @Getter/@Setter (PLAN.md Finding #17) generate the trivial accessors below; getReason/setReason
// stay hand-written (Lombok skips generating an accessor when one with that name already exists)
// because they must keep running copyReason's defensive-copy/validation and getReason's
// empty-map-not-null behavior (Finding #13) - neither is something @Getter/@Setter produce.
// equals()/hashCode() are hand-written below, not Lombok-generated - for the same reason as
// getReason/setReason: this class needs to control its own semantics rather than take Lombok's
// default. They stay id-only (constraintId alone) since the locking design elsewhere in this
// module leans on that. No @EqualsAndHashCode annotation here: Lombok skips generating
// equals/hashCode whenever hand-written ones already exist in the class, so one was never used.
@Getter
@Setter
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
        // Defensive copy, matching Task.copyOf's philosophy of never handing out (or storing) a
        // reference the caller can use to mutate this object's state from the outside.
        this.reason = copyReason(reason);
        this.sourceRef = sourceRef;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // this.reason is always already the result of copyReason() (set that way in the
    // constructor and in setReason()), so it is already an unmodifiable, independent copy -
    // re-copying it again on every read is redundant (review finding #14).
    // Returns an empty map rather than null (PLAN.md Finding #13) - matches this module's own
    // "empty collection, never null" convention (see TaskDependencyRepository/TaskRepository),
    // and avoids wrapping a Map in Optional, which Effective Java Item 55 advises against.
    public Map<String, Object> getReason() {
        return this.reason == null ? Collections.emptyMap() : this.reason;
    }

    public void setReason(Map<String, Object> reason) { this.reason = copyReason(reason); }

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
            // Arrays are exactly as mutable and exactly as uncopied by new HashMap<>(reason)
            // below as a Collection/Map value would be, but instanceof Collection/Map alone
            // doesn't catch them (arrays implement neither interface) - PLAN.md Finding #14.
            if (value instanceof Collection || value instanceof Map
                    || (value != null && value.getClass().isArray())) {
                throw new IllegalArgumentException(
                    "Constraint reason value for key '" + entry.getKey() + "' must not be a "
                        + "Collection, Map, or array - reason only supports flat scalar values, "
                        + "since the defensive copy does not recurse into nested mutable structures");
            }
        }
        return Collections.unmodifiableMap(new HashMap<>(reason));
    }

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
