package com.rnd.bff.controller;

import com.rnd.bff.dto.EmployeeTaskDTO;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.dto.TaskListResponseDTO;
import com.rnd.bff.dto.TaskRecommendationDTO;
import com.rnd.bff.dto.TaskSelectionDTO;
import com.rnd.bff.dto.TaskStatusUpdateDTO;
import com.rnd.bff.dto.WorkspaceRequestDTO;
import com.rnd.bff.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Workspace", description = "Called only by the Gateway, never directly by the Frontend")
public class WorkspaceController {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceController.class);

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Operation(summary = "Recommend a task", description = "Best-fit task for the given available time.")
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
    @Operation(summary = "List tasks", description = "Filter, title search, and pagination for My Tasks.")
    @GetMapping("/workspace/tasks")
    public ResponseEntity<TaskListResponseDTO> listTasks(
            @Parameter(description = "all | in_progress | blocked | waiting_on_me (default all)")
            @RequestParam(required = false) String filter,
            @Parameter(description = "Free-text, matched against task title (case-insensitive)")
            @RequestParam(required = false) String search,
            @Parameter(description = "0-based page index")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1-100")
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader("X-User-Id") String userId) {

        logger.info(
                "Task list request - userId: {}, filter: {}, search: {}, page: {}, size: {}",
                userId, filter, search, page, size);

        return ResponseEntity.ok(workspaceService.listTasks(userId, filter, search, page, size));
    }

    /**
     * Inbox's pick of the tasks to do in the available time.
     *
     * GET /workspace/tasks/selection?availableMinutes=60
     *
     * availableMinutes: required, 1-480. Mapped before /workspace/tasks/{taskId},
     * and Spring prefers this literal path over the {taskId} pattern anyway.
     */
    @Operation(summary = "Select tasks for the available time",
            description = "The tasks that fit availableMinutes together, in suggested order (Inbox's task "
                    + "selection). usedFallback=true: nothing fit, so the shortest task was picked anyway. "
                    + "400 if availableMinutes is missing or not 1-480.")
    @GetMapping("/workspace/tasks/selection")
    public ResponseEntity<TaskSelectionDTO> selectTasks(
            @Parameter(description = "Free time in minutes, 1-480")
            @RequestParam(required = false) Integer availableMinutes,
            @RequestHeader("X-User-Id") String userId) {

        logger.info("Task selection request - userId: {}, availableMinutes: {}", userId, availableMinutes);

        return ResponseEntity.ok(workspaceService.selectTasks(userId, availableMinutes));
    }

    /**
     * Get task details by ID.
     *
     * GET /workspace/tasks/{taskId}
     */
    @Operation(summary = "Get task details", description = "404 if the task id doesn't exist.")
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
    @Operation(summary = "Update task status", description = "404 if the task id doesn't exist.")
    @PatchMapping("/workspace/tasks/{taskId}/status")
    public ResponseEntity<TaskDTO> updateTaskStatus(
            @PathVariable String taskId,
            @Valid @RequestBody TaskStatusUpdateDTO body,
            @RequestHeader("X-User-Id") String userId) {

        logger.info(
                "Task status update - userId: {}, taskId: {}, newStatus: {}",
                userId, taskId, body.getStatus());

        return ResponseEntity.ok(workspaceService.updateTaskStatus(userId, taskId, body.getStatus(), body.getSnoozedUntil()));
    }

    /**
     * My Workspace's chat/blocking-dependency demo task.
     *
     * GET /workspace/employee-task
     */
    @Operation(summary = "Get my current task", description = "The employee's current task for the My Workspace demo.")
    @GetMapping("/workspace/employee-task")
    public ResponseEntity<EmployeeTaskDTO> getMyTask(@RequestHeader("X-User-Id") String userId) {

        logger.info("Employee task request - userId: {}", userId);

        return ResponseEntity.ok(workspaceService.getMyTask(userId));
    }
}
