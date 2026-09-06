package com.rnd.bff.controller;

import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.dto.TaskListResponseDTO;
import com.rnd.bff.dto.TaskRecommendationDTO;
import com.rnd.bff.dto.TaskStatusUpdateDTO;
import com.rnd.bff.dto.WorkspaceRequestDTO;
import com.rnd.bff.service.WorkspaceService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Employee workspace recommendations.
 *
 * Gateway forwards POST /api/workspace/recommend here as POST
 * /workspace/recommend (StripPrefix=1), with the caller's identity in
 * X-User-Id — see docs/workspace-screen-integration-plan.md.
 */
@RestController
public class WorkspaceController {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceController.class);

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @PostMapping("/workspace/recommend")
    public ResponseEntity<TaskRecommendationDTO> recommend(
            @Valid @RequestBody WorkspaceRequestDTO request,
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(workspaceService.recommend(request, userId));
    }

    /**
     * "My Tasks" screen: full task list with filter, title search, and
     * pagination.
     *
     * GET /workspace/tasks?filter=&search=&page=&size=
     *
     * filter: all | in_progress | blocked | waiting_on_me (default all)
     * page: 0-based (default 0), size: default 20, clamped to [1, 100]
     */
    @GetMapping("/workspace/tasks")
    public ResponseEntity<TaskListResponseDTO> listTasks(
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader("X-User-Id") String userId) {

        logger.info(
                "Task list request - userId: {}, filter: {}, search: {}, page: {}, size: {}",
                userId, filter, search, page, size);

        return ResponseEntity.ok(workspaceService.listTasks(userId, filter, search, page, size));
    }

    /**
     * Get task details by ID.
     *
     * GET /workspace/tasks/{taskId}
     */
    @GetMapping("/workspace/tasks/{taskId}")
    public ResponseEntity<TaskDTO> getTask(
            @PathVariable String taskId,
            @RequestHeader("X-User-Id") String userId) {

        logger.info("Task details request - userId: {}, taskId: {}", userId, taskId);

        return ResponseEntity.ok(workspaceService.getTask(userId, taskId));
    }

    /**
     * Update the status of a task.
     *
     * PATCH /workspace/tasks/{taskId}/status
     * Body: { "status": "IN_PROGRESS" }
     */
    @PatchMapping("/workspace/tasks/{taskId}/status")
    public ResponseEntity<TaskDTO> updateTaskStatus(
            @PathVariable String taskId,
            @Valid @RequestBody TaskStatusUpdateDTO body,
            @RequestHeader("X-User-Id") String userId) {

        logger.info(
                "Task status update - userId: {}, taskId: {}, newStatus: {}",
                userId, taskId, body.getStatus());

        return ResponseEntity.ok(workspaceService.updateTaskStatus(userId, taskId, body.getStatus()));
    }
}
