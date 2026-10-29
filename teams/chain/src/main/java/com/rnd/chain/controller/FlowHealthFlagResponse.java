package com.rnd.chain.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.rnd.chain.dto.FlowHealthEntityType;
import com.rnd.chain.dto.FlowHealthFlag;
import com.rnd.chain.dto.FlowHealthPattern;
import com.rnd.chain.dto.FlowHealthSeverity;

import java.time.Instant;
import java.util.List;

/** API-layer DTO for {@link FlowHealthFlag}. */
public record FlowHealthFlagResponse(String flagId, FlowHealthPattern pattern, FlowHealthEntityType entityType,
                                     List<String> entityIds, FlowHealthSeverity severity, double score, int rank,
                                     String explanation, JsonNode evidence, Instant runAt) {

    public static FlowHealthFlagResponse from(FlowHealthFlag flag) {
        return new FlowHealthFlagResponse(flag.flagId(), flag.pattern(), flag.entityType(), flag.entityIds(),
                flag.severity(), flag.score(), flag.rank(), flag.explanation(), flag.evidence(), flag.runAt());
    }
}
