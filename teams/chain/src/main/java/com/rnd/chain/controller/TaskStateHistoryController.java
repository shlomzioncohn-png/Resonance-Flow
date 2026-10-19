package com.rnd.chain.controller;

import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.service.TaskStateHistoryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Read-only access to a task's status / blocking history (Issue #775). */
@RestController
@RequestMapping("/tasks")
public class TaskStateHistoryController {

    private final TaskStateHistoryService taskStateHistoryService;

    public TaskStateHistoryController(TaskStateHistoryService taskStateHistoryService) {
        this.taskStateHistoryService = taskStateHistoryService;
    }

    /** {@code from} is inclusive, {@code to} exclusive, both ISO-8601 instants; omitted means open. */
    @GetMapping("/{taskId}/state-history")
    public ResponseEntity<PageResponse<TaskStateHistoryResponse>> getStateHistory(
            @PathVariable UUID taskId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(
                taskStateHistoryService.getHistory(taskId, from, to, page, size)
                        .map(TaskStateHistoryResponse::from));
    }
}
