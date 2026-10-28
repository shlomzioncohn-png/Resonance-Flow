package com.rnd.chain.controller;

import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.dto.TaskBlockedSince;
import com.rnd.chain.dto.TaskStateEventType;
import com.rnd.chain.service.TaskStateHistoryService;
import com.rnd.chain.service.TenantStateHistoryService;
import com.rnd.chain.tenant.TaskOwnershipService;
import com.rnd.chain.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only access to a task's status / blocking history (Issue #775). */
@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskStateHistoryController {

    private final TaskStateHistoryService taskStateHistoryService;
    private final TenantContext tenantContext;
    private final TaskOwnershipService taskOwnershipService;
    private final TenantStateHistoryService tenantStateHistoryService;

    /**
     * All of the caller's tenant events, oldest first. {@code from} (inclusive) and {@code to}
     * (exclusive) are required, at most 90 days apart. {@code after} is the previous page's
     * {@code nextCursor}. An event committed after the cursor passed its position can be missed.
     */
    @GetMapping("/state-history")
    public ResponseEntity<TaskStateHistoryPageResponse> getTenantStateHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) TaskStateEventType eventType,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(TaskStateHistoryPageResponse.from(
                tenantStateHistoryService.getHistory(
                        tenantContext.requireTenantId(), from, to, eventType, after, size)));
    }

    /** Currently blocked tasks of the caller's tenant; {@code taskIds} optionally narrows the result. */
    @GetMapping("/blocked-since")
    public ResponseEntity<List<TaskBlockedSince>> getBlockedSince(
            @RequestParam(required = false) List<UUID> taskIds) {

        return ResponseEntity.ok(
                tenantStateHistoryService.getBlockedSince(tenantContext.requireTenantId(), taskIds));
    }

    /** {@code from} is inclusive, {@code to} exclusive, both ISO-8601 instants; omitted means open. */
    @GetMapping("/{taskId}/state-history")
    public ResponseEntity<PageResponse<TaskStateHistoryResponse>> getStateHistory(
            @PathVariable UUID taskId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        taskOwnershipService.requireTaskInTenant(taskId, tenantContext.requireTenantId());
        return ResponseEntity.ok(
                taskStateHistoryService.getHistory(taskId, from, to, page, size)
                        .map(TaskStateHistoryResponse::from));
    }
}
