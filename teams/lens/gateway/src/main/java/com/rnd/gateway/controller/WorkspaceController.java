package com.rnd.gateway.controller;

import com.rnd.gateway.dto.TaskRecommendationDTO;
import com.rnd.gateway.dto.WorkspaceRequestDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * WorkspaceController - Employee Workspace Recommendations
 *
 * Purpose: Route task recommendation requests from Frontend to BFF
 *
 * Endpoint:
 * POST /api/workspace/recommend
 * - Input: WorkspaceRequestDTO (userId, availableTime)
 * - Output: TaskRecommendationDTO (recommended task + explanation)
 *
 * Flow:
 * Frontend → Gateway (this controller) → BFF
 *            (WorkspaceRequestDTO sent)
 *            Gateway ← BFF (returns TaskRecommendationDTO)
 */
@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceController.class);

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
     *   "userId": "emp_001",
     *   "availableTime": 45,
     *   "projectFilter": null,
     *   "minPriority": null
     * }
     *
     * Response: {
     *   "task": {
     *     "id": "task_001",
     *     "title": "Review API changes",
     *     "status": "READY",
     *     "priority": "HIGH",
     *     "effortEstimate": 35,
     *     "owner": "Rachel",
     *     ...
     *   },
     *   "explanation": "Fits in 45 minutes, High priority, no blockers",
     *   "fitsInTime": true,
     *   "isHighPriority": true,
     *   "hasNoDependencies": true,
     *   "confidenceScore": 85
     * }
     */
    @PostMapping("/recommend")
    public ResponseEntity<TaskRecommendationDTO> getRecommendedTask(
            @RequestBody WorkspaceRequestDTO request) {

        logger.info("Workspace recommendation request - userId: {}, availableTime: {} minutes",
                request.getUserId(), request.getAvailableTime());

        try {
            // Forward request to BFF
            String bffUrl = bffServiceUrl + "/workspace/recommend";

            logger.debug("Forwarding to BFF: {}", bffUrl);

            TaskRecommendationDTO recommendation = restTemplate.postForObject(
                    bffUrl,
                    request,
                    TaskRecommendationDTO.class
            );

            logger.info("Received recommendation from BFF for user: {}", request.getUserId());
            logger.debug("Recommended task: {}", recommendation.getTask().getTitle());

            return ResponseEntity.ok(recommendation);

        } catch (Exception e) {
            logger.error("Error getting task recommendation from BFF", e);
            throw new RuntimeException("Failed to get task recommendation: " + e.getMessage());
        }
    }
}
