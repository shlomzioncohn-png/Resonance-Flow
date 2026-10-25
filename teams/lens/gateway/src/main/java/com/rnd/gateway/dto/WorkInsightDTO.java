package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Work Insights response from BFF to Frontend.
 *
 * Flow:
 * Frontend → Gateway → BFF → Inbox
 *            Gateway ← BFF ← Inbox (returns insight + evidence)
 *            Frontend
 *
 * Inbox returns snake_case; BFF transforms to camelCase for the Frontend.
 * Only non-null fields are included in the response (JsonInclude.NON_NULL).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkInsightDTO {

    /** Unique identifier for this insight, returned by Inbox. */
    private String insightId;

    /** Status of the insight: READY, NOT_ENOUGH_DATA, or NO_INSIGHT. */
    private String status;

    /** Length of the analyzed period, in days. */
    private Integer periodDays;

    /** The insight text, present only when status is READY. */
    private String insight;

    /** Recommendation based on the insight, may be omitted even when status is READY. */
    private String recommendation;

    /** Evidence supporting the insight, present only when status is READY. */
    private InsightEvidenceDTO evidence;
}
