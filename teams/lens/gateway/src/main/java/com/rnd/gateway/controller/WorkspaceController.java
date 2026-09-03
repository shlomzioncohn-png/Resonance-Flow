package com.rnd.gateway.controller;

import com.rnd.gateway.dto.TaskDTO;
import com.rnd.gateway.dto.TaskRecommendationDTO;
import com.rnd.gateway.dto.TaskStatusUpdateDTO;
import com.rnd.gateway.dto.WorkspaceRequestDTO;
import com.rnd.gateway.exception.GatewayTimeoutException;
import com.rnd.gateway.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import jakarta.validation.Valid;

import java.net.SocketTimeoutException;

/**
 * WorkspaceController - Employee Workspace screen
 *
 * Routes all workspace-related requests from Frontend to BFF.
 *
 * Endpoints:
 * POST  /api/workspace/recommend             - Get best task recommendation for available time
 * GET   /api/workspace/tasks/{taskId}        - Get full task details (dependencies, tags, etc.)
 * PATCH /api/workspace/tasks/{taskId}/status - Update task status (READY/IN_PROGRESS/BLOCKED/COMPLETED)
 *
 * Identity flow (current: MOCK):
 *   TODO: replace mock userId with real JWT extraction.
 *   In production: Gateway validates the Authorization header,
 *   extracts userId from the JWT "sub" claim,
 *   and forwards it to BFF as X-User-Id header.
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

    @Value("${bff.endpoints.workspace-recommend:/workspace/recommend}")
    private String bffWorkspaceRecommendPath;

    @Value("${bff.endpoints.tasks:/workspace/tasks}")
    private String bffTasksPath;

    @Value("${bff.read-timeout-ms:10000}")
    private long bffReadTimeoutMs;

    @Value("${app.dev-mode:false}")
    private boolean devMode;

    public WorkspaceController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Get mock user ID for dev/test flow.
     * Only returns mock ID if dev-mode is explicitly enabled.
     * In production (dev-mode=false), this call will fail with an error
     * until proper JWT extraction is implemented.
     * 
     * @param endpoint endpoint identifier for logging
     * @return MOCK_USER_ID if dev-mode enabled
     * @throws IllegalStateException if dev-mode is not enabled (production guard)
     */
    private String getMockUserId(String endpoint) {
        if (!devMode) {
            logger.error("Attempt to use mock user ID in production mode for endpoint: {}", endpoint);
            throw new IllegalStateException(
                "Mock user ID cannot be used in production. " +
                "JWT extraction must be implemented for: " + endpoint
            );
        }
        logger.warn("Using mock user ID for endpoint: {} (dev-mode enabled)", endpoint);
        return MOCK_USER_ID;
    }

    /**
     * Builds the X-User-Id header for BFF requests.
     * Centralised here so JWT extraction only needs to change in one place.
     */
    private HttpHeaders buildHeaders(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId);
        return headers;
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
        String userId = getMockUserId("workspace.recommend");

        logger.info("Workspace recommendation request - userId: {}, availableTime: {} minutes",
                userId, request.getAvailableTime());

        try {
            String bffUrl = UriComponentsBuilder.fromHttpUrl(bffServiceUrl)
                    .path(bffWorkspaceRecommendPath)
                    .toUriString();
            logger.debug("Forwarding to BFF: {}", bffUrl);

            HttpEntity<WorkspaceRequestDTO> employeeRecommendation =
                    new HttpEntity<>(request, buildHeaders(userId));

            TaskRecommendationDTO recommendation = restTemplate.postForObject(
                    bffUrl,
                    employeeRecommendation,
                    TaskRecommendationDTO.class
            );

            if (recommendation == null) {
                throw new ServiceUnavailableException("bff", "BFF returned empty response");
            }

            logger.info("Received recommendation from BFF for user: {}", userId);
            if (recommendation.getTask() != null) {
                logger.debug("Recommended task: {}", recommendation.getTask().getTitle());
            }

            return ResponseEntity.ok(recommendation);

        } catch (ServiceUnavailableException | GatewayTimeoutException | ResponseStatusException e) {
            throw e;
        } catch (ResourceAccessException e) {
            // BFF timed out (read timeout) or connection was refused / host unreachable
            if (e.getCause() instanceof SocketTimeoutException) {
                logger.error("BFF read timeout for user: {}", userId, e);
                throw new GatewayTimeoutException("bff", bffReadTimeoutMs,
                        "BFF did not respond in time");
            }
            logger.error("BFF unreachable for user: {}", userId, e);
            throw new ServiceUnavailableException("bff", "BFF is currently unavailable");

        } catch (HttpStatusCodeException e) {
            // BFF returned a 4xx/5xx — preserve the status code, hide internal details
            logger.warn("BFF returned {} for user: {}", e.getStatusCode(), userId);
            throw new ResponseStatusException(e.getStatusCode(),
                    "Downstream service returned an error");

        } catch (Exception e) {
            logger.error("Unexpected error forwarding workspace request for user: {}", userId, e);
            throw new ServiceUnavailableException("bff", "Unexpected error communicating with BFF");
        }
    }

    /**
     * Get full task details by ID
     *
     * Request: GET /api/workspace/tasks/{taskId}
     *
     * Response: TaskDTO with all fields including dependencies, constraints,
     *           source, sprint, tags, blockingReason
     */
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<TaskDTO> getTaskDetails(@PathVariable String taskId) {

        String userId = getMockUserId("workspace.details"); // TODO: extract from JWT

        logger.info("Task details request - userId: {}, taskId: {}", userId, taskId);

        try {
            String bffUrl = UriComponentsBuilder.fromHttpUrl(bffServiceUrl)
                    .path(bffTasksPath + "/{taskId}")
                    .buildAndExpand(taskId)
                    .toUriString();
            logger.debug("Forwarding to BFF: {}", bffUrl);

            HttpEntity<Void> getTaskRequest = new HttpEntity<>(buildHeaders(userId));

            ResponseEntity<TaskDTO> bffResponse = restTemplate.exchange(
                    bffUrl, HttpMethod.GET, getTaskRequest, TaskDTO.class);

            logger.info("Received task details from BFF - taskId: {}", taskId);

            TaskDTO taskDetails = bffResponse.getBody();
            if (taskDetails == null) {
                throw new ServiceUnavailableException("bff", "BFF returned empty response");
            }
            return ResponseEntity.ok(taskDetails);

        } catch (ServiceUnavailableException | GatewayTimeoutException | ResponseStatusException e) {
            throw e;
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                logger.error("BFF read timeout for taskId: {}", taskId, e);
                throw new GatewayTimeoutException("bff", bffReadTimeoutMs, "BFF did not respond in time");
            }
            logger.error("BFF unreachable for taskId: {}", taskId, e);
            throw new ServiceUnavailableException("bff", "BFF is currently unavailable");

        } catch (HttpStatusCodeException e) {
            logger.warn("BFF returned {} for taskId: {}", e.getStatusCode(), taskId);
            throw new ResponseStatusException(e.getStatusCode(), "Downstream service returned an error");

        } catch (Exception e) {
            logger.error("Unexpected error fetching task details for taskId: {}", taskId, e);
            throw new ServiceUnavailableException("bff", "Unexpected error communicating with BFF");
        }
    }

    /**
     * Update task status
     *
     * Request: PATCH /api/workspace/tasks/{taskId}/status
     * Body: { "status": "IN_PROGRESS" }
     *
     * Allowed status values: READY, IN_PROGRESS, BLOCKED, COMPLETED
     *
     * Response: updated TaskDTO
     */
    @PatchMapping("/tasks/{taskId}/status")
    public ResponseEntity<TaskDTO> updateTaskStatus(
            @PathVariable String taskId,
            @Valid @RequestBody TaskStatusUpdateDTO statusUpdate) {

        String userId = getMockUserId("workspace.status-update"); // TODO: extract from JWT

        logger.info("Task status update request - userId: {}, taskId: {}, newStatus: {}",
                userId, taskId, statusUpdate.getStatus());

        try {
            String bffUrl = UriComponentsBuilder.fromHttpUrl(bffServiceUrl)
                    .path(bffTasksPath + "/{taskId}/status")
                    .buildAndExpand(taskId)
                    .toUriString();
            logger.debug("Forwarding to BFF: {}", bffUrl);

            HttpEntity<TaskStatusUpdateDTO> statusUpdateRequest =
                    new HttpEntity<>(statusUpdate, buildHeaders(userId));

            ResponseEntity<TaskDTO> bffResponse = restTemplate.exchange(
                    bffUrl, HttpMethod.PATCH, statusUpdateRequest, TaskDTO.class);

            logger.info("Task status updated - taskId: {}, newStatus: {}",
                    taskId, statusUpdate.getStatus());

            TaskDTO updatedTask = bffResponse.getBody();
            if (updatedTask == null) {
                throw new ServiceUnavailableException("bff", "BFF returned empty response");
            }
            return ResponseEntity.ok(updatedTask);

        } catch (ServiceUnavailableException | GatewayTimeoutException | ResponseStatusException e) {
            throw e;
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                logger.error("BFF read timeout for taskId: {}", taskId, e);
                throw new GatewayTimeoutException("bff", bffReadTimeoutMs, "BFF did not respond in time");
            }
            logger.error("BFF unreachable for taskId: {}", taskId, e);
            throw new ServiceUnavailableException("bff", "BFF is currently unavailable");

        } catch (HttpStatusCodeException e) {
            logger.warn("BFF returned {} for taskId: {}", e.getStatusCode(), taskId);
            throw new ResponseStatusException(e.getStatusCode(), "Downstream service returned an error");

        } catch (Exception e) {
            logger.error("Unexpected error updating task status for taskId: {}", taskId, e);
            throw new ServiceUnavailableException("bff", "Unexpected error communicating with BFF");
        }
    }
}
