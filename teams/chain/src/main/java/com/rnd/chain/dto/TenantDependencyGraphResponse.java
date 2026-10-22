package com.rnd.chain.dto;

import java.util.List;

public record TenantDependencyGraphResponse(
        List<TenantDependencyGraphNode> nodes,
        List<TenantDependencyGraphEdge> edges,
        boolean hasMore
) {
}