package com.rnd.bff.controller;

import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.dto.TaskRecommendationDTO;
import com.rnd.bff.dto.TaskStatusUpdateDTO;
import com.rnd.bff.dto.WorkspaceRequestDTO;
import com.rnd.bff.service.WorkspaceService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

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
     * Get task details by ID.
     *
     * GET /workspace/tasks/{taskId}
     *
     * TODO: forward to Inbox service once its API is live.
     */
    @GetMapping("/workspace/tasks/{taskId}")
    public ResponseEntity<TaskDTO> getTask(
            @PathVariable String taskId,
            @RequestHeader("X-User-Id") String userId) {

        logger.info("Task details request - userId: {}, taskId: {}", userId, taskId);

        // TODO: replace with Inbox call: inboxClient.getTask(userId, taskId)
        TaskDTO task = TaskDTO.builder()
                .id(taskId)
                .title("Review API changes")
                .description("Review the latest API changes and update integration tests.")
                .status("READY")
                .priority("HIGH")
                .effortEstimate(35)
                .dueDate(LocalDateTime.of(2026, 8, 30, 17, 0))
                .owner("Alex Morgan")
                .project("Platform Service")
                .build();

        return ResponseEntity.ok(task);
    }

    /**
     * Update the status of a task.
     *
     * PATCH /workspace/tasks/{taskId}/status
     * Body: { "status": "IN_PROGRESS" }
     *
     * TODO: forward to Inbox service once its API is live.
     * For now returns the mock task with the updated status so the
     * full Gateway → BFF flow is provable end-to-end.
     */
    @PatchMapping("/workspace/tasks/{taskId}/status")
    public ResponseEntity<TaskDTO> updateTaskStatus(
            @PathVariable String taskId,
            @Valid @RequestBody TaskStatusUpdateDTO body,
            @RequestHeader("X-User-Id") String userId) {

        String status = body.getStatus();
        logger.info("Task status update - userId: {}, taskId: {}, newStatus: {}", userId, taskId, status);

        // TODO: replace with Inbox call: inboxClient.updateTaskStatus(userId, taskId, status)
        TaskDTO updated = TaskDTO.builder()
                .id(taskId)
                .title("Review API changes")
                .description("Review the latest API changes and update integration tests.")
                .status(status)
                .priority("HIGH")
                .effortEstimate(35)
                .dueDate(LocalDateTime.of(2026, 8, 30, 17, 0))
                .owner("Alex Morgan")
                .project("Platform Service")
                .build();

        return ResponseEntity.ok(updated);
    }
}
