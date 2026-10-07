package com.rnd.chain.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record UnresolvedDependency(
        UUID id,
        UUID taskId,
        UUID dependsOnTaskId,
        DependencyChangeSource source,
        String sourceRef,
        String reason,
        Instant createdAt
) {

    public UnresolvedDependency {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(
                dependsOnTaskId,
                "dependsOnTaskId must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(sourceRef, "sourceRef must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}