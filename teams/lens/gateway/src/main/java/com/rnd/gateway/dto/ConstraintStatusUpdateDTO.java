package com.rnd.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for changing a constraint's status. Mirrors
 * com.rnd.bff.dto.ConstraintStatusUpdateDTO field-for-field.
 *
 * Used by: PATCH /api/manager/tasks/{taskId}/constraints/{constraintId}
 *
 * Example:
 * {
 *   "status": "SATISFIED"
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConstraintStatusUpdateDTO {

    /** New status: PENDING, SATISFIED or WAIVED. */
    @NotBlank(message = "status is required")
    @Pattern(
        regexp = "PENDING|SATISFIED|WAIVED",
        message = "status must be one of: PENDING, SATISFIED, WAIVED"
    )
    private String status;
}
