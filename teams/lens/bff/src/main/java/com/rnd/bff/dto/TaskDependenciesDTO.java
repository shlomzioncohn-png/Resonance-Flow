package com.rnd.bff.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Chain's feasibility answer for one task: what it depends on and what's
 * blocking it. Keyed by task id in ChainClient's response — see
 * docs/workspace-screen-integration-plan.md.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskDependenciesDTO {
    private List<String> dependencies;
    private List<ConstraintDTO> constraints;
}
