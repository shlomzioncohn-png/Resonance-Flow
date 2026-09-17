package com.rnd.chain.controller;

import com.rnd.chain.dto.DependencyChangeAction;
import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;

import java.time.Instant;
import java.util.UUID;

/** API-layer DTO for {@link DependencyHistoryEntry}, kept separate so JSON-shape changes never touch the domain model. */
public record DependencyHistoryResponse(UUID id, UUID taskId, UUID dependsOnTaskId,
                                         DependencyChangeAction action, DependencyChangeSource source,
                                         String sourceRef, Instant createdAt) {

    public static DependencyHistoryResponse from(DependencyHistoryEntry entry) {
        return new DependencyHistoryResponse(entry.id(), entry.taskId(), entry.dependsOnTaskId(),
                entry.action(), entry.source(), entry.sourceRef(), entry.createdAt());
    }
}
