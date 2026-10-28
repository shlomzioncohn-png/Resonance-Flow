
package com.rnd.chain.controller;

import com.rnd.chain.dto.AddDependencyRequest;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.service.TaskDependencyService;
import com.rnd.chain.tenant.TaskOwnershipService;
import com.rnd.chain.tenant.TenantContext;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
@Tag(
        name = "Task Dependencies",
        description = "Manage dependencies between tasks"
)
public class TaskDependencyController {

    private final TaskDependencyService taskDependencyService;
    private final TenantContext tenantContext;
    private final TaskOwnershipService taskOwnershipService;

    @Operation(
            summary = "Add task dependency",
            description = "Creates a dependency between a task and another task"
    )
    @PostMapping("/{taskId}/dependencies")
    public ResponseEntity<TaskDependency> addDependency(
            @PathVariable UUID taskId,
            @Valid @RequestBody AddDependencyRequest request) {

        taskOwnershipService.requireTaskInTenant(taskId, tenantContext.requireTenantId());
        TaskDependency createdDependency =
                taskDependencyService.addDependency(
                        taskId,
                        request.getDependsOnTaskId());

        URI location = URI.create(
                "/tasks/"
                        + taskId
                        + "/dependencies/"
                        + createdDependency.dependsOnTaskId());

        return ResponseEntity
                .created(location)
                .body(createdDependency);
    }

    @Operation(
            summary = "Remove task dependency",
            description = "Removes an existing dependency between two tasks"
    )
    @DeleteMapping("/{taskId}/dependencies/{dependsOnTaskId}")
    public ResponseEntity<Void> removeDependency(
            @PathVariable UUID taskId,
            @PathVariable UUID dependsOnTaskId) {

        taskOwnershipService.requireTaskInTenant(taskId, tenantContext.requireTenantId());
        taskDependencyService.removeDependency(
                taskId,
                dependsOnTaskId);

        return ResponseEntity
                .noContent()
                .build();
    }



}