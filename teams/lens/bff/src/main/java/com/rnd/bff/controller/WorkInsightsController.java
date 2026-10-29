package com.rnd.bff.controller;

import com.rnd.bff.dto.InsightFeedbackRequestDTO;
import com.rnd.bff.dto.WorkInsightDTO;
import com.rnd.bff.service.WorkInsightsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Employee "Work Insights" screen (called only by the Gateway, never directly by the Frontend).
 *
 * Gateway forwards /api/work-insights/** here as /work-insights/**, with
 * the caller's identity in X-User-Id. The BFF maps this mock id to a real
 * Inbox user (inbox.dev-actor-id) before calling Inbox (see WorkInsightsService).
 */
@RestController
@RequestMapping("/work-insights")
@Tag(name = "Work Insights", description = "Called only by the Gateway, never directly by the Frontend")
public class WorkInsightsController {

    private static final Logger logger = LoggerFactory.getLogger(WorkInsightsController.class);

    private final WorkInsightsService workInsightsService;

    public WorkInsightsController(WorkInsightsService workInsightsService) {
        this.workInsightsService = workInsightsService;
    }

    @Operation(summary = "Get today's work insight", description = "Returns today's AI-generated work insight. May take up to 90 seconds on first call of the day (AI processing).")
    @GetMapping
    public ResponseEntity<WorkInsightDTO> getWorkInsight(
            @RequestHeader("X-User-Id") String userId) {

        logger.info("Work insight request - actorId: {}", userId);
        return ResponseEntity.ok(workInsightsService.getWorkInsight(userId));
    }

    @Operation(summary = "Send feedback on a work insight", description = "Submit feedback on whether a work insight was helpful. Returns 204 No Content on success.")
    @PostMapping("/{insightId}/feedback")
    public ResponseEntity<Void> sendInsightFeedback(
            @Parameter(description = "ID of the insight to provide feedback for") @PathVariable String insightId,
            @Valid @RequestBody InsightFeedbackRequestDTO body,
            @RequestHeader("X-User-Id") String userId) {

        logger.info("Work insight feedback request - actorId: {}, insightId: {}, helpful: {}",
                userId, insightId, body.getHelpful());
        workInsightsService.sendInsightFeedback(userId, insightId, body.getHelpful());
        return ResponseEntity.noContent().build();
    }
}
