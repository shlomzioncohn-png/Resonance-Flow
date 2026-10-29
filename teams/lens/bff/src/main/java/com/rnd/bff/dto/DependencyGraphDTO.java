package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The manager's Bottlenecks screen: the tenant's task dependency graph. Same
 * shape as the frontend's services/bottlenecksApi.ts - don't change a field
 * without it.
 */
public record DependencyGraphDTO(List<Node> nodes, List<Edge> edges) {

    /**
     * @param status      Chain's: PENDING, ACTIVE, DONE
     * @param isBlocked   at least one task this one depends on isn't done yet
     * @param blocksCount how many tasks depend on this one, directly or indirectly
     */
    public record Node(String id, String title, String status,
                       @JsonProperty("isBlocked") boolean isBlocked, int blocksCount) {
    }

    /** source blocks target. confidence is 0-1. */
    public record Edge(String source, String target, double confidence) {
    }
}
