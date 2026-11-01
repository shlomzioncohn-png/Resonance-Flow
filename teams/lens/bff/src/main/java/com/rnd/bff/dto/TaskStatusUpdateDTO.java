package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;

import java.time.Instant;
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
 *
 * Snoozing:
 * {
 *   "status": "SNOOZED",
 *   "snoozedUntil": "2026-10-12T09:00:00Z"
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskStatusUpdateDTO {

    /**
     * New status for the task. Must be one of the 8 canonical values:
     * NEW, IN_REVIEW, APPROVED, IN_PROGRESS, SNOOZED, DONE, REJECTED, CANCELLED.
     * See teams/lens/docs/employee-workspace/employee-workspace-api-contract.md §5.0 for the status model change.
     */
    @NotBlank(message = "status is required")
    @Pattern(
        regexp = "NEW|IN_REVIEW|APPROVED|IN_PROGRESS|SNOOZED|DONE|REJECTED|CANCELLED",
        message = "status must be one of: NEW, IN_REVIEW, APPROVED, IN_PROGRESS, SNOOZED, DONE, REJECTED, CANCELLED"
    )
    private String status;

    /**
     * Required by Inbox when status is SNOOZED: when the task wakes up again
     * (ISO-8601, e.g. "2026-10-12T09:00:00Z"). Ignored for any other status.
     * Inbox enforces the "required" rule and answers 400 without it.
     */
    private Instant snoozedUntil;

    /**
     * Why the task was rejected - only with status REJECTED, optional there.
     * Sent to Inbox as feedback_text; Inbox answers 400 if it comes with any
     * other status. Inbox has no category field, so the Frontend puts the
     * category at the start of the text.
     */
    private String feedbackText;
}
