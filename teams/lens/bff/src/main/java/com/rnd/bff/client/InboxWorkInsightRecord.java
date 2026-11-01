package com.rnd.bff.client;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Wire shape of Inbox's {@code GET /api/v1/work-insights} response
 * (Inbox's WorkInsightResponseDto), used only to deserialize WorkInsightsClient's
 * RestTemplate call. Field names mirror Inbox's JSON 1:1 in snake_case.
 *
 * Inbox returns one of three states:
 * - READY: insight + recommendation (optional) + evidence (optional)
 * - NOT_ENOUGH_DATA: only periodDays
 * - NO_INSIGHT: only periodDays (new status, not yet in Frontend)
 *
 * This is never returned to the Frontend as-is; WorkInsightsService transforms
 * it to camelCase WorkInsightDTO at the boundary, converting snake_case field
 * names to camelCase.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class InboxWorkInsightRecord {

    private String insightId;
    private String status;
    private Integer periodDays;
    /** Present only when status is READY */
    private String insight;
    /** Optional even when status is READY */
    private String recommendation;
    /** Present only when status is READY */
    private Evidence evidence;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Evidence {
        private String text;
        /** Omitted if evidence is not a ratio */
        private Integer achieved;
        /** Omitted if evidence is not a ratio */
        private Integer total;
    }
}
