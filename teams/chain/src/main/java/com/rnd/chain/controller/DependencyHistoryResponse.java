package com.rnd.chain.controller;

import com.rnd.chain.dto.DependencyChangeAction;
import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;

import java.time.LocalDateTime;
import java.util.UUID;

/** API-layer DTO for {@link DependencyHistoryEntry}, kept separate so JSON-shape changes never touch the domain model. */
public final class DependencyHistoryResponse {

    private final UUID id;
    private final UUID taskId;
    private final UUID dependsOnTaskId;
    private final DependencyChangeAction action;
    private final DependencyChangeSource source;
    private final String sourceRef;
    private final LocalDateTime createdAt;

    public DependencyHistoryResponse(UUID id, UUID taskId, UUID dependsOnTaskId,
                                      DependencyChangeAction action, DependencyChangeSource source,
                                      String sourceRef, LocalDateTime createdAt) {
        this.id = id;
        this.taskId = taskId;
        this.dependsOnTaskId = dependsOnTaskId;
        this.action = action;
        this.source = source;
        this.sourceRef = sourceRef;
        this.createdAt = createdAt;
    }

    public static DependencyHistoryResponse from(DependencyHistoryEntry entry) {
        return new DependencyHistoryResponse(entry.getId(), entry.getTaskId(), entry.getDependsOnTaskId(),
                entry.getAction(), entry.getSource(), entry.getSourceRef(), entry.getCreatedAt());
    }

    public UUID getId() {
        return id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public UUID getDependsOnTaskId() {
        return dependsOnTaskId;
    }

    public DependencyChangeAction getAction() {
        return action;
    }

    public DependencyChangeSource getSource() {
        return source;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
