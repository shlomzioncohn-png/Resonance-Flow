package com.rnd.chain.dto;

import java.time.Instant;
import java.util.UUID;

public record TenantDependencyGraphNode(
        UUID taskId,
        TaskStatus status,
        boolean isReady,
        int blockingCount,
        int transitiveDependentsCount,
        Instant blockedSince
) {
}