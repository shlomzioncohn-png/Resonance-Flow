package com.rnd.bff.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Chain's GET /tenants/{tenantId}/dependency-graph: the tenant's tasks, highest-impact
 * blockers first, cut at the requested limit (hasMore = some were left out). Edges only
 * between returned tasks. Statuses are Chain's: PENDING, ACTIVE, DONE.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainDependencyGraph(List<Node> nodes, List<Edge> edges, boolean hasMore) {

    /**
     * @param blockingCount             how many unfinished tasks this one waits on
     * @param transitiveDependentsCount how many tasks wait on this one, directly or indirectly
     * @param blockedSince              set only while the task isn't ready
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Node(String taskId, String status, @JsonProperty("isReady") boolean isReady,
                       int blockingCount, int transitiveDependentsCount, Instant blockedSince) {
    }

    /**
     * taskId depends on (is blocked by) dependsOnTaskId. Chain doesn't send the edge's
     * confidence yet - null until it does.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Edge(String taskId, String dependsOnTaskId, Double confidence) {
    }
}
