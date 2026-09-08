package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for updating a task's status. Mirrors
 * com.rnd.gateway.dto.TaskStatusUpdateDTO field-for-field — each layer owns
 * its own copy of the DTO rather than sharing a library module. See
 * docs/workspace-screen-integration-plan.md.
 *
 * Used by: PATCH /workspace/tasks/{taskId}/status
 *
 * Example:
 * {
 *   "status": "IN_PROGRESS"
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskStatusUpdateDTO {

    /**
     * New status for the task.
     * Allowed values: READY, IN_PROGRESS, BLOCKED, COMPLETED
     */
    @NotBlank(message = "status is required")
    @Pattern(
        regexp = "READY|IN_PROGRESS|BLOCKED|COMPLETED",
        message = "status must be one of: READY, IN_PROGRESS, BLOCKED, COMPLETED"
    )
    private String status;
}
