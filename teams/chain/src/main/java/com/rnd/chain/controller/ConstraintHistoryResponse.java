package com.rnd.chain.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.ConstraintHistoryAction;
import com.rnd.chain.dto.ConstraintHistoryEntry;

import java.time.Instant;
import java.util.UUID;

/** API-layer DTO for {@link ConstraintHistoryEntry}; null fields (e.g. previousStatus on CREATED) are omitted from the JSON. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConstraintHistoryResponse(UUID id, String constraintId, UUID taskId,
                                         ConstraintHistoryAction action,
                                         Constraint.ConstraintStatus previousStatus,
                                         Constraint.ConstraintStatus newStatus,
                                         String sourceRef, Instant createdAt) {

    public static ConstraintHistoryResponse from(ConstraintHistoryEntry entry) {
        return new ConstraintHistoryResponse(entry.id(), entry.constraintId(), entry.taskId(),
                entry.action(), entry.previousStatus(), entry.newStatus(),
                entry.sourceRef(), entry.createdAt());
    }
}
