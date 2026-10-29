package com.rnd.bff.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for changing a constraint's status. The BFF sends the same
 * shape on to Chain (com.rnd.chain.dto.UpdateConstraintStatusRequest).
 *
 * Used by: PATCH /manager/tasks/{taskId}/constraints/{constraintId}
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

    /** New status: PENDING, SATISFIED or WAIVED - Chain's Constraint.ConstraintStatus values. */
    @NotBlank(message = "status is required")
    @Pattern(
        regexp = "PENDING|SATISFIED|WAIVED",
        message = "status must be one of: PENDING, SATISFIED, WAIVED"
    )
    private String status;
}
