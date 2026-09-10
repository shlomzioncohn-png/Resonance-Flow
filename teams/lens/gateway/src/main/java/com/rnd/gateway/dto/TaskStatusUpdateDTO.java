package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * TaskStatusUpdate DTO — Request body for updating a task's status.
 *
 * Used by: PATCH /api/workspace/tasks/{taskId}/status
 *
 * Example:
 * {
 *   "status": "IN_PROGRESS"
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
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
