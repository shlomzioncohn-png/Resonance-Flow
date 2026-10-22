package com.rnd.chain.dto;

import java.util.UUID;

public record TenantDependencyGraphEdge(
        UUID taskId,
        UUID dependsOnTaskId
) {
}