
package com.rnd.chain.controller;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskAvailability;
import com.rnd.chain.service.DependencyGraphService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/tasks")
@Tag(
        name = "Dependency Graph",
        description = "Query blockers and task hierarchy relationships"
)
public class DependencyGraphController {

    private final DependencyGraphService dependencyGraphService;

    public DependencyGraphController(
            DependencyGraphService dependencyGraphService) {

        this.dependencyGraphService = dependencyGraphService;
    }

    @Operation(
            summary = "Get task blockers",
            description = "Returns the blocking tasks and open constraints for the specified task"
    )
    @GetMapping("/{taskId}/blockers")
    public ResponseEntity<TaskAvailability> getBlockers(
            @PathVariable UUID taskId) {

        TaskAvailability blockers =
                dependencyGraphService.getBlockers(taskId);

        return ResponseEntity.ok(blockers);
    }

    @Operation(
            summary = "Get task ancestors",
            description = "Returns all ancestor tasks in the task hierarchy"
    )
    @GetMapping("/{taskId}/ancestors")
    public ResponseEntity<List<Task>> getAncestors(
            @PathVariable UUID taskId) {

        List<Task> ancestors =
                dependencyGraphService.getAncestors(taskId);

        return ResponseEntity.ok(ancestors);
    }

    @Operation(
            summary = "Get task descendants",
            description = "Returns all descendant tasks in the task hierarchy"
    )
    @GetMapping("/{taskId}/descendants")
    public ResponseEntity<List<Task>> getDescendants(
            @PathVariable UUID taskId) {

        List<Task> descendants =
                dependencyGraphService.getDescendants(taskId);

        return ResponseEntity.ok(descendants);
    }
}