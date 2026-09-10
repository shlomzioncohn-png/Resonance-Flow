package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A task/work item, aggregated from Inbox (task data), Chain (dependencies),
 * and Mind (scoring) once those integrations exist. Mirrors
 * com.rnd.gateway.dto.TaskDTO field-for-field — each layer owns its own copy
 * of the DTO rather than sharing a library module. See
 * docs/workspace-screen-integration-plan.md.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskDTO {

    private String id;
    private String title;
    private String description;

    /** READY / IN_PROGRESS / BLOCKED / COMPLETED */
    private String status;

    /** LOW / MEDIUM / HIGH */
    private String priority;

    /** Estimated effort, in minutes. */
    private Integer effortEstimate;

    private LocalDateTime dueDate;
    private String owner;
    private String project;

    /** Task IDs this task depends on. Empty list = no dependencies. */
    private List<String> dependencies;

    /** Blockers, e.g. "approval_needed". Empty list = no constraints. */
    private List<String> constraints;
}
