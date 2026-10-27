package com.rnd.bff.controller;

import com.rnd.bff.dto.TaskActivityDTO;
import com.rnd.bff.service.TaskActivityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The employee task page's Activity tab. Gateway forwards
 * GET /api/workspace/tasks/{taskId}/activity here with the caller in X-User-Id.
 * The tenant is never a parameter: the BFF reads it from the user's Inbox record.
 */
@RestController
@Tag(name = "Workspace", description = "Called only by the Gateway, never directly by the Frontend")
public class TaskActivityController {

    private static final Logger logger = LoggerFactory.getLogger(TaskActivityController.class);

    private final TaskActivityService taskActivityService;

    public TaskActivityController(TaskActivityService taskActivityService) {
        this.taskActivityService = taskActivityService;
    }

    @Operation(summary = "Task activity",
            description = "Chain's status/blocking and dependency history for one task, merged, newest first. "
                    + "If one history fails the other is still returned, flagged. 503 only when both fail.")
    @GetMapping("/workspace/tasks/{taskId}/activity")
    public ResponseEntity<TaskActivityDTO> getActivity(
            @PathVariable String taskId,
            @RequestHeader("X-User-Id") String userId) {

        logger.info("Task activity request - userId: {}, taskId: {}", userId, taskId);
        return ResponseEntity.ok(taskActivityService.getActivity(userId, taskId));
    }
}
