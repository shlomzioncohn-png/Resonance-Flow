package com.rnd.chain.controller;

import com.rnd.chain.dto.DependencyHistoryEntry;
import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.dto.TaskAvailability;
import com.rnd.chain.dto.TaskResponse;
import com.rnd.chain.dto.TaskTraceResponse;
import com.rnd.chain.service.DependencyGraphService;
import com.rnd.chain.service.TaskDependencyService;
import com.rnd.chain.service.TaskTraversalService;
import com.rnd.chain.tenant.TaskOwnershipService;
import com.rnd.chain.tenant.TenantContext;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class ChainController {

    private final TaskTraversalService taskTraversalService;
    private final TaskDependencyService taskDependencyService;
    private final DependencyGraphService dependencyGraphService;
    private final TenantContext tenantContext;
    private final TaskOwnershipService taskOwnershipService;

    @GetMapping("/{taskId}/dependencies")
    public ResponseEntity<List<TaskResponse>> getDirectDependencies(
            @PathVariable UUID taskId) {

        taskOwnershipService.requireTaskInTenant(taskId, tenantContext.requireTenantId());
        return ResponseEntity.ok(
                taskTraversalService.getDirectDependencies(taskId));
    }

    @GetMapping("/{taskId}/dependencies/transitive")
    public ResponseEntity<List<TaskResponse>> getAllDependencies(
            @PathVariable UUID taskId) {

        taskOwnershipService.requireTaskInTenant(taskId, tenantContext.requireTenantId());
        return ResponseEntity.ok(
                taskTraversalService.getAllDependencies(taskId));
    }

    @GetMapping("/{taskId}/dependencies/history")
    public ResponseEntity<PageResponse<DependencyHistoryResponse>> getHistory(
            @PathVariable UUID taskId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        taskOwnershipService.requireTaskInTenant(taskId, tenantContext.requireTenantId());
        PageResponse<DependencyHistoryEntry> entries =
                taskDependencyService.getHistory(
                        taskId,
                        page,
                        size);

        return ResponseEntity.ok(
                entries.map(DependencyHistoryResponse::from));
    }

    @GetMapping("/{taskId}/dependents")
    public ResponseEntity<List<TaskResponse>> getDirectDependents(
            @PathVariable UUID taskId) {

        taskOwnershipService.requireTaskInTenant(taskId, tenantContext.requireTenantId());
        return ResponseEntity.ok(
                taskTraversalService.getDirectDependents(taskId));
    }

    @GetMapping("/{taskId}/dependents/transitive")
    public ResponseEntity<List<TaskResponse>> getAllDependents(
            @PathVariable UUID taskId) {

        taskOwnershipService.requireTaskInTenant(taskId, tenantContext.requireTenantId());
        return ResponseEntity.ok(
                taskTraversalService.getAllDependents(taskId));
    }

    @GetMapping("/{taskId}/ancestors")
    public ResponseEntity<List<TaskResponse>> getAncestors(
            @PathVariable UUID taskId) {

        taskOwnershipService.requireTaskInTenant(taskId, tenantContext.requireTenantId());
        return ResponseEntity.ok(
                taskTraversalService.getAncestors(taskId));
    }

    @GetMapping("/{taskId}/children")
    public ResponseEntity<List<TaskResponse>> getDirectChildren(
            @PathVariable UUID taskId) {

        taskOwnershipService.requireTaskInTenant(taskId, tenantContext.requireTenantId());
        return ResponseEntity.ok(
                taskTraversalService.getDirectChildren(taskId));
    }

    @GetMapping("/{taskId}/descendants")
    public ResponseEntity<List<TaskResponse>> getAllDescendants(
            @PathVariable UUID taskId) {

        taskOwnershipService.requireTaskInTenant(taskId, tenantContext.requireTenantId());
        return ResponseEntity.ok(
                taskTraversalService.getAllDescendants(taskId));
    }

    @GetMapping("/{taskId}/trace")
    public ResponseEntity<TaskTraceResponse> getTrace(
            @PathVariable UUID taskId) {

        taskOwnershipService.requireTaskInTenant(taskId, tenantContext.requireTenantId());
        return ResponseEntity.ok(
                taskTraversalService.getTrace(taskId));
    }

    @GetMapping("/{taskId}/blockers")
    public ResponseEntity<TaskAvailability> getBlockers(
            @PathVariable UUID taskId) {

        taskOwnershipService.requireTaskInTenant(taskId, tenantContext.requireTenantId());
        return ResponseEntity.ok(
                dependencyGraphService.getBlockers(taskId));
    }
}