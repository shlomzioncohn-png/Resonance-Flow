package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One flag as Chain's GET /flow-health/flags returns it. Internal to the BFF:
 * the frontend gets {@link FlowHealthFlagDTO}, which adds task names.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChainFlowHealthFlagDTO {

    private String flagId;
    /** CHRONIC_BLOCKER, RECURRING_CYCLE or SLOW_CHAIN. */
    private String pattern;
    /** task, edge, edge_pair or chain. */
    private String entityType;
    /** Task ids; edges are "taskId>dependsOnTaskId"; a chain is its tasks in order. */
    private List<String> entityIds;
    /** low, medium or high. */
    private String severity;
    /** 0..1 percentile within the detector. */
    private double score;
    private int rank;
    private String explanation;
    /** Detector-specific; passed through untouched. */
    private JsonNode evidence;
    private String runAt;
}
