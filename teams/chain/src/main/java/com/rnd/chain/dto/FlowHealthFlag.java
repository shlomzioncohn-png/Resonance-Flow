package com.rnd.chain.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One flow-health flag. {@code score} is a 0..1 percentile within its detector, so it only orders flags of
 * the same pattern; {@code rank} is the overall order the job assigned. {@code evidence} is detector-specific
 * and kept as raw JSON.
 */
public record FlowHealthFlag(String flagId, FlowHealthPattern pattern, FlowHealthEntityType entityType,
                             List<String> entityIds, FlowHealthSeverity severity, double score, int rank,
                             String explanation, JsonNode evidence, Instant runAt) {

    public FlowHealthFlag {
        Objects.requireNonNull(flagId, "flagId must not be null");
        Objects.requireNonNull(pattern, "pattern must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(entityIds, "entityIds must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(runAt, "runAt must not be null");
        if (score < 0 || score > 1) {
            throw new IllegalArgumentException("score must be in 0..1: " + score);
        }
        entityIds = List.copyOf(entityIds);
    }
}
