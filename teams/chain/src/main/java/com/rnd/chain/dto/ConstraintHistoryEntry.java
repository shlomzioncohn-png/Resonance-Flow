package com.rnd.chain.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable audit record of one constraint change; {@code id} is null until persisted (DB-generated), {@code previousStatus} is null for CREATED. */
public record ConstraintHistoryEntry(UUID id,
                                     String constraintId,
                                     UUID taskId,
                                     ConstraintHistoryAction action,
                                     Constraint.ConstraintStatus previousStatus,
                                     Constraint.ConstraintStatus newStatus,
                                     String sourceRef,
                                     Instant createdAt) {

    public ConstraintHistoryEntry {
        Objects.requireNonNull(constraintId, "constraintId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
