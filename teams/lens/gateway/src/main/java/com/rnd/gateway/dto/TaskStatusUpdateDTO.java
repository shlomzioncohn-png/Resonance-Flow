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
     * New status for the task. Must be one of the 8 canonical values:
     * NEW, IN_REVIEW, APPROVED, IN_PROGRESS, SNOOZED, DONE, REJECTED, CANCELLED.
     * See docs/employee-workspace-api-contract.md §5.0 for the status model change.
     */
    @NotBlank(message = "status is required")
    @Pattern(
        regexp = "NEW|IN_REVIEW|APPROVED|IN_PROGRESS|SNOOZED|DONE|REJECTED|CANCELLED",
        message = "status must be one of: NEW, IN_REVIEW, APPROVED, IN_PROGRESS, SNOOZED, DONE, REJECTED, CANCELLED"
    )
    private String status;
}
