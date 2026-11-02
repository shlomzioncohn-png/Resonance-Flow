package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * One constraint on a task (from Chain, via the BFF). Mirrors
 * com.rnd.bff.dto.ConstraintDTO field-for-field. Only a manager changes a
 * constraint's status, via PATCH /api/manager/tasks/{taskId}/constraints/{constraintId}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConstraintDTO {

    private String constraintId;

    /** APPROVAL_NEEDED, BLOCKED_BY_INFO or DATE_WINDOW. */
    private String type;

    /** PENDING (open), SATISFIED or WAIVED. */
    private String status;

    /** Free-form details from Chain (flat scalar values). */
    private Map<String, Object> reason;

    /** Where the constraint came from, as Chain sends it. May be null. */
    private String sourceRef;

    /** ISO local date-time, e.g. "2026-10-20T09:15:30". */
    private String createdAt;
}
