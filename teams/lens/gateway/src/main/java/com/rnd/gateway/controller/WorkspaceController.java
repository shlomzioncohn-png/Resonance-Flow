package com.rnd.gateway.controller;

import com.rnd.gateway.dto.TaskRecommendationDTO;
import com.rnd.gateway.dto.WorkspaceRequestDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import jakarta.validation.Valid;

/**
 * WorkspaceController - Employee Workspace Recommendations
 *
 * Purpose: Route task recommendation requests from Frontend to BFF
 *
 * Endpoint:
 * POST /api/workspace/recommend
 * - Input:  WorkspaceRequestDTO (availableTime, optional filters)
 * - Output: TaskRecommendationDTO (recommended task + explanation)
 *
 * Identity flow (current: MOCK):
 *   TODO: replace mock userId with real JWT extraction.
 *   In production: Gateway will validate the Authorization header,
 *   extract the userId from the JWT "sub" claim,
 *   and forward it to BFF as X-User-Id header.
 */
@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceController.class);

    // TODO: replace with real JWT extraction service when Auth & Identity is implemented
    private static final String MOCK_USER_ID = "emp_001";

    private final RestTemplate restTemplate;

    @Value("${bff.service.url:http://bff-service:8080}")
    private String bffServiceUrl;

    public WorkspaceController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Get task recommendation for employee
     *
     * Request: POST /api/workspace/recommend
     * Body: {
     *   "availableTime": 45,
     *   "projectFilter": null,
     *   "minPriority": null
     * }
     *
     * Response: {
     *   "task": { ... },
     *   "explanation": "Fits in 45 minutes, High priority, no blockers",
     *   "fitsInTime": true,
     *   "isHighPriority": true,
     *   "hasNoDependencies": true,
     *   "confidenceScore": 85
     * }
     */
    @PostMapping("/recommend")
    public ResponseEntity<TaskRecommendationDTO> getRecommendedTask(
            @Valid @RequestBody WorkspaceRequestDTO request) {

        // TODO: extract userId from JWT token (Authorization: Bearer <token>)
        //       JwtUtil.extractUserId(request.getHeader("Authorization"))
        //       For now: using mock userId so the full flow FE → Gateway → BFF works
        String userId = MOCK_USER_ID;

        logger.info("Workspace recommendation request - userId: {}, availableTime: {} minutes",
                userId, request.getAvailableTime());

        try {
            String bffUrl = bffServiceUrl + "/workspace/recommend";
            logger.debug("Forwarding to BFF: {}", bffUrl);

            // Forward userId to BFF via header
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-User-Id", userId);
            HttpEntity<WorkspaceRequestDTO> entity = new HttpEntity<>(request, headers);

            TaskRecommendationDTO recommendation = restTemplate.postForObject(
                    bffUrl,
                    entity,
                    TaskRecommendationDTO.class
            );

            logger.info("Received recommendation from BFF for user: {}", userId);
            logger.debug("Recommended task: {}", recommendation.getTask().getTitle());

            return ResponseEntity.ok(recommendation);

        } catch (Exception e) {
            logger.error("Error getting task recommendation from BFF", e);
            throw new RuntimeException("Failed to get task recommendation: " + e.getMessage());
        }
    }
}
