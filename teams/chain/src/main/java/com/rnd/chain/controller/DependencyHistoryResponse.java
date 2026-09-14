package com.rnd.chain.controller;

import com.rnd.chain.dto.DependencyChangeAction;
import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;

import java.time.LocalDateTime;
import java.util.UUID;

/** API-layer DTO for {@link DependencyHistoryEntry}, kept separate so JSON-shape changes never touch the domain model. */
public record DependencyHistoryResponse(UUID id, UUID taskId, UUID dependsOnTaskId,
                                         DependencyChangeAction action, DependencyChangeSource source,
                                         String sourceRef, LocalDateTime createdAt) {

    public static DependencyHistoryResponse from(DependencyHistoryEntry entry) {
        return new DependencyHistoryResponse(entry.getId(), entry.getTaskId(), entry.getDependsOnTaskId(),
                entry.getAction(), entry.getSource(), entry.getSourceRef(), entry.getCreatedAt());
    }
}
