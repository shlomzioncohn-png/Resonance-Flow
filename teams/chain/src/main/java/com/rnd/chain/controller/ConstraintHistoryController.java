package com.rnd.chain.controller;

import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.service.ConstraintHistoryService;
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
import java.util.UUID;

/** Read-only access to a task's constraint history (Issue #875). */
@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class ConstraintHistoryController {

    private final ConstraintHistoryService constraintHistoryService;
    private final TenantContext tenantContext;
    private final TaskOwnershipService taskOwnershipService;

    /** {@code from} is inclusive, {@code to} exclusive, both ISO-8601 instants; omitted means open. */
    @GetMapping("/{taskId}/constraints/history")
    public ResponseEntity<PageResponse<ConstraintHistoryResponse>> getConstraintHistory(
            @PathVariable UUID taskId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        taskOwnershipService.requireTaskInTenant(taskId, tenantContext.requireTenantId());
        return ResponseEntity.ok(
                constraintHistoryService.getHistory(taskId, from, to, page, size)
                        .map(ConstraintHistoryResponse::from));
    }
}
