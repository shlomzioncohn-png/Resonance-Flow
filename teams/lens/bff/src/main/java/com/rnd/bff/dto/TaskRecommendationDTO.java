package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response to a workspace recommendation request: the best-fit task plus the
 * reasoning behind it. See docs/workspace-screen-integration-plan.md.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskRecommendationDTO {

    private TaskDTO task;

    /** Human-readable reason this task was recommended. */
    private String explanation;

    private Boolean fitsInTime;
    private Boolean isHighPriority;
    private Boolean hasNoDependencies;

    /** 0-100, from Mind's scoring. */
    private Integer confidenceScore;

    /** Alternatives if the employee can't do the recommended task. Empty = none. */
    private List<TaskDTO> alternatives;
}
