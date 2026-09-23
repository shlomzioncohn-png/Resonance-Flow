package com.rnd.gateway.controller;

import com.rnd.gateway.dto.EmployeeTaskDTO;
import com.rnd.gateway.dto.TaskDTO;
import com.rnd.gateway.dto.TaskListResponseDTO;
import com.rnd.gateway.dto.TaskRecommendationDTO;
import com.rnd.gateway.dto.TaskStatusUpdateDTO;
import com.rnd.gateway.dto.WorkspaceRequestDTO;
import com.rnd.gateway.exception.GatewayTimeoutException;
import com.rnd.gateway.exception.ServiceUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.RequestParam;
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
 * GET   /api/workspace/employee-task         - Get the employee's current task (My Workspace demo)
 *
 * Identity flow (current: MOCK):
 *   TODO: replace mock userId with real JWT extraction.
 *   In production: Gateway validates the Authorization header,
 *   extracts userId from the JWT "sub" claim,
 *   and forwards it to BFF as X-User-Id header.
 */
@RestController
@RequestMapping("/api/workspace")
@Tag(name = "Workspace", description = "Employee My Workspace / My Tasks screens — forwards to BFF")
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

    @Value("${bff.endpoints.employee-task:/workspace/employee-task}")
    private String bffEmployeeTaskPath;

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
    @Operation(summary = "Find my best task",
            description = "\"I have X minutes — what should I work on?\" Returns one recommended task.")
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
     * Get the employee's full task list ("My Tasks" screen), with filter,
     * title search, and pagination.
     *
     * Request: GET /api/workspace/tasks?filter=&search=&page=&size=
     *
     * filter/search/page/size are all optional here and only forwarded to
     * BFF when present — unlike /recommend's body fields, defaults for these
     * live in BFF (WorkspaceService.listTasks), not in the Gateway. The
     * Gateway is a transparent proxy for this endpoint: it forwards exactly
     * what the Frontend sent, nothing more.
     *
     * Response: TaskListResponseDTO { items, total, page, size }
     */
    @Operation(summary = "List my tasks",
            description = "\"My Tasks\" screen — filter, title search, and pagination.")
    @GetMapping("/tasks")
    public ResponseEntity<TaskListResponseDTO> getTaskList(
            @Parameter(description = "all | in_progress | blocked | waiting_on_me (default all)")
            @RequestParam(required = false) String filter,
            @Parameter(description = "Free-text, matched against task title (case-insensitive)")
            @RequestParam(required = false) String search,
            @Parameter(description = "0-based page index (default 0)")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size (default 20, clamped to 1-100)")
            @RequestParam(required = false) Integer size) {

        String userId = getMockUserId("workspace.tasks.list"); // TODO: extract from JWT

        logger.info("Task list request - userId: {}, filter: {}, search: {}, page: {}, size: {}",
                userId, filter, search, page, size);

        try {
            UriComponentsBuilder bffUrlBuilder = UriComponentsBuilder.fromHttpUrl(bffServiceUrl)
                    .path(bffTasksPath);
            if (filter != null) {
                bffUrlBuilder.queryParam("filter", filter);
            }
            if (search != null) {
                bffUrlBuilder.queryParam("search", search);
            }
            if (page != null) {
                bffUrlBuilder.queryParam("page", page);
            }
            if (size != null) {
                bffUrlBuilder.queryParam("size", size);
            }
            String bffUrl = bffUrlBuilder.toUriString();
            logger.debug("Forwarding to BFF: {}", bffUrl);

            HttpEntity<Void> listRequest = new HttpEntity<>(buildHeaders(userId));

            ResponseEntity<TaskListResponseDTO> bffResponse = restTemplate.exchange(
                    bffUrl, HttpMethod.GET, listRequest, TaskListResponseDTO.class);

            TaskListResponseDTO taskList = bffResponse.getBody();
            if (taskList == null) {
                throw new ServiceUnavailableException("bff", "BFF returned empty response");
            }

            logger.info("Received task list from BFF - userId: {}, total: {}", userId, taskList.getTotal());
            return ResponseEntity.ok(taskList);

        } catch (ServiceUnavailableException | GatewayTimeoutException | ResponseStatusException e) {
            throw e;
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                logger.error("BFF read timeout for task list - userId: {}", userId, e);
                throw new GatewayTimeoutException("bff", bffReadTimeoutMs, "BFF did not respond in time");
            }
            logger.error("BFF unreachable for task list - userId: {}", userId, e);
            throw new ServiceUnavailableException("bff", "BFF is currently unavailable");

        } catch (HttpStatusCodeException e) {
            logger.warn("BFF returned {} for task list - userId: {}", e.getStatusCode(), userId);
            throw new ResponseStatusException(e.getStatusCode(), "Downstream service returned an error");

        } catch (Exception e) {
            logger.error("Unexpected error fetching task list for userId: {}", userId, e);
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
    @Operation(summary = "Get task details", description = "Full task detail panel, by task id.")
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
    @Operation(summary = "Update task status",
            description = "Allowed values: NEW, IN_REVIEW, APPROVED, IN_PROGRESS, SNOOZED, DONE, REJECTED, CANCELLED. See docs/employee-workspace-api-contract.md §5.0 for status model.")
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

    /**
     * Get the employee's current task (My Workspace chat/blocking-dependency demo).
     *
     * Request: GET /api/workspace/employee-task
     *
     * Response: EmployeeTaskDTO
     */
    @Operation(summary = "Get my current task", description = "The employee's current task for the My Workspace demo.")
    @GetMapping("/employee-task")
    public ResponseEntity<EmployeeTaskDTO> getMyTask() {

        String userId = getMockUserId("workspace.employee-task"); // TODO: extract from JWT

        logger.info("Employee task request - userId: {}", userId);

        try {
            String bffUrl = UriComponentsBuilder.fromHttpUrl(bffServiceUrl)
                    .path(bffEmployeeTaskPath)
                    .toUriString();
            logger.debug("Forwarding to BFF: {}", bffUrl);

            HttpEntity<Void> getTaskRequest = new HttpEntity<>(buildHeaders(userId));

            ResponseEntity<EmployeeTaskDTO> bffResponse = restTemplate.exchange(
                    bffUrl, HttpMethod.GET, getTaskRequest, EmployeeTaskDTO.class);

            EmployeeTaskDTO employeeTask = bffResponse.getBody();
            if (employeeTask == null) {
                throw new ServiceUnavailableException("bff", "BFF returned empty response");
            }

            logger.info("Received employee task from BFF - userId: {}", userId);
            return ResponseEntity.ok(employeeTask);

        } catch (ServiceUnavailableException | GatewayTimeoutException | ResponseStatusException e) {
            throw e;
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                logger.error("BFF read timeout for employee task - userId: {}", userId, e);
                throw new GatewayTimeoutException("bff", bffReadTimeoutMs, "BFF did not respond in time");
            }
            logger.error("BFF unreachable for employee task - userId: {}", userId, e);
            throw new ServiceUnavailableException("bff", "BFF is currently unavailable");

        } catch (HttpStatusCodeException e) {
            logger.warn("BFF returned {} for employee task - userId: {}", e.getStatusCode(), userId);
            throw new ResponseStatusException(e.getStatusCode(), "Downstream service returned an error");

        } catch (Exception e) {
            logger.error("Unexpected error fetching employee task for userId: {}", userId, e);
            throw new ServiceUnavailableException("bff", "Unexpected error communicating with BFF");
        }
    }
}
