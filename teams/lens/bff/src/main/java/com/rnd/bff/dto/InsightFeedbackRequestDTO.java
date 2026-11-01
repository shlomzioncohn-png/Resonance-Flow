package com.rnd.bff.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for sending feedback on a work insight.
 *
 * Frontend sends: POST /api/work-insights/{insightId}/feedback
 * BFF forwards to Inbox: POST /api/v1/work-insights/{insightId}/feedback
 * Response: 204 No Content
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsightFeedbackRequestDTO {

    /** Whether the insight was helpful. Required - Inbox rejects feedback without it. */
    @NotNull(message = "helpful is required")
    private Boolean helpful;
}
