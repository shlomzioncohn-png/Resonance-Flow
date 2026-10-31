package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * One constraint on a task, as Chain returns it from
 * GET /tasks/{taskId}/constraints (com.rnd.chain.dto.Constraint). Read-only
 * in LENS - the BFF never changes a constraint's status.
 *
 * Fields Chain sends that LENS doesn't show (taskId, sourceRef, updatedAt)
 * are ignored.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConstraintDTO {

    public static final String STATUS_PENDING = "PENDING";

    private String constraintId;

    /** APPROVAL_NEEDED, BLOCKED_BY_INFO or DATE_WINDOW. */
    private String type;

    /** PENDING (open), SATISFIED or WAIVED. */
    private String status;

    /** Free-form details from Chain (flat scalar values). Chain sends {} when there are none. */
    private Map<String, Object> reason;

    /** As Chain sends it (ISO local date-time, e.g. "2026-10-20T09:15:30"). */
    private String createdAt;

    /** How many of these are still open (PENDING). null list -> 0. */
    public static int countOpen(List<ConstraintDTO> constraints) {
        if (constraints == null) {
            return 0;
        }
        return (int) constraints.stream()
                .filter(c -> c != null && STATUS_PENDING.equals(c.getStatus()))
                .count();
    }
}
