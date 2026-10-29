package com.rnd.bff.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** A flow-health flag with its entities resolved to task names (read-only, a recommendation). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowHealthFlagDTO {

    private String flagId;
    @Schema(description = "Overall order assigned by the analytics job; 1 is the most important", example = "1")
    private int rank;
    @Schema(description = "CHRONIC_BLOCKER, RECURRING_CYCLE or SLOW_CHAIN")
    private String pattern;
    @Schema(description = "task, edge, edge_pair or chain")
    private String entityType;
    private List<FlowHealthEntityDTO> entities;
    @Schema(description = "low, medium or high")
    private String severity;
    @Schema(description = "0..1 percentile within the detector - only comparable inside one pattern")
    private double score;
    private String explanation;
    @Schema(description = "Detector-specific details, as Chain sent them")
    private JsonNode evidence;
    @Schema(description = "Projects of the tasks involved, deduplicated")
    private List<FlowHealthProjectDTO> projects;
}
