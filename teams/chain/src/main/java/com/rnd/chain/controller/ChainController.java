package com.rnd.chain.controller;

import com.rnd.chain.dto.AddDependencyRequest;
import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.CreateConstraintRequest;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskAvailability;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskEligibilityResponse;
import com.rnd.chain.dto.UpdateConstraintStatusRequest;

import com.rnd.chain.service.ConstraintService;
import com.rnd.chain.service.DependencyGraphService;
import com.rnd.chain.service.TaskDependencyService;
import com.rnd.chain.service.TaskEligibilityService;

import javax.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/tasks")
public class ChainController {

    private final TaskDependencyService taskDependencyService;
    private final DependencyGraphService dependencyGraphService;
    private final ConstraintService constraintService;
    private final TaskEligibilityService taskEligibilityService;

    public ChainController(
            TaskDependencyService taskDependencyService,
            DependencyGraphService dependencyGraphService,
            ConstraintService constraintService,
            TaskEligibilityService taskEligibilityService
    ) {
        this.taskDependencyService = taskDependencyService;
        this.dependencyGraphService = dependencyGraphService;
        this.constraintService = constraintService;
        this.taskEligibilityService = taskEligibilityService;
    }

    // =========================================================
    // Stage 1 - Dependency Management
    // =========================================================

    // 1. Add dependency
    @PostMapping("/{taskId}/dependencies")
    public ResponseEntity<TaskDependency> addDependency(
            @PathVariable UUID taskId,
            @Valid @RequestBody AddDependencyRequest request
    ) {

        TaskDependency createdDependency =
                taskDependencyService.addDependency(
                        taskId,
                        request.getDependsOnTaskId()
                );

        URI location = URI.create(
                "/tasks/"
                        + taskId
                        + "/dependencies/"
                        + createdDependency.getDependsOnTaskId()
        );

        return ResponseEntity
                .created(location)
                .body(createdDependency);
    }

    // 2. Remove dependency
    @DeleteMapping("/{taskId}/dependencies/{dependsOnTaskId}")
    public ResponseEntity<Void> removeDependency(
            @PathVariable UUID taskId,
            @PathVariable UUID dependsOnTaskId
    ) {

        taskDependencyService.removeDependency(
                taskId,
                dependsOnTaskId
        );

        return ResponseEntity
                .noContent()
                .build();
    }

    // 3. Get direct dependencies
    @GetMapping("/{taskId}/dependencies")
    public ResponseEntity<List<UUID>> getDependencies(
            @PathVariable UUID taskId
    ) {

        List<UUID> dependencies =
                taskDependencyService.getDependencies(taskId);

        return ResponseEntity.ok(dependencies);
    }

    // 4. Get dependents
    @GetMapping("/{taskId}/dependents")
    public ResponseEntity<List<UUID>> getDependents(
            @PathVariable UUID taskId
    ) {

        List<UUID> dependents =
                taskDependencyService.getDependents(taskId);

        return ResponseEntity.ok(dependents);
    }

    // =========================================================
    // Stage 2 - Dependency Traversal
    // =========================================================

    // 5. Get direct blockers and open constraints
    @GetMapping("/{taskId}/blockers")
    public ResponseEntity<TaskAvailability> getBlockers(
            @PathVariable UUID taskId
    ) {

        TaskAvailability blockers =
                dependencyGraphService.getBlockers(taskId);

        return ResponseEntity.ok(blockers);
    }

    // 6. Get all ancestors
    @GetMapping("/{taskId}/ancestors")
    public ResponseEntity<List<Task>> getAncestors(
            @PathVariable UUID taskId
    ) {

        List<Task> ancestors =
                dependencyGraphService.getAncestors(taskId);

        return ResponseEntity.ok(ancestors);
    }

    // 7. Get all descendants
    @GetMapping("/{taskId}/descendants")
    public ResponseEntity<List<Task>> getDescendants(
            @PathVariable UUID taskId
    ) {

        List<Task> descendants =
                dependencyGraphService.getDescendants(taskId);

        return ResponseEntity.ok(descendants);
    }

    // =========================================================
    // Stage 3 - Constraint Management
    // =========================================================

    // 8. Create constraint
    @PostMapping("/{taskId}/constraints")
    public ResponseEntity<Constraint> createConstraint(
            @PathVariable UUID taskId,
            @Valid @RequestBody CreateConstraintRequest request
    ) {

        Constraint createdConstraint =
                constraintService.createConstraint(
                        taskId,
                        request.getType(),
                        request.getReason(),
                        request.getSourceRef()
                );

        URI location = URI.create(
                "/tasks/"
                        + taskId
                        + "/constraints/"
                        + createdConstraint.getConstraintId()
        );

        return ResponseEntity
                .created(location)
                .body(createdConstraint);
    }

    // 9. Get task constraints
    @GetMapping("/{taskId}/constraints")
    public ResponseEntity<List<Constraint>> getTaskConstraints(
            @PathVariable UUID taskId
    ) {

        List<Constraint> constraints =
                constraintService.getTaskConstraints(taskId);

        return ResponseEntity.ok(constraints);
    }

    // 10. Update constraint status
    @PatchMapping("/{taskId}/constraints/{constraintId}")
    public ResponseEntity<Constraint> updateConstraintStatus(
            @PathVariable UUID taskId,
            @PathVariable String constraintId,
            @Valid @RequestBody UpdateConstraintStatusRequest request
    ) {

        Constraint updatedConstraint =
                constraintService.updateConstraintStatus(
                        constraintId,
                        request.getStatus()
                );

        return ResponseEntity.ok(updatedConstraint);
    }

    // =========================================================
    // Stage 4 - Task Eligibility
    // =========================================================

    // 11. Get task eligibility
    @GetMapping("/{taskId}/eligibility")
    public ResponseEntity<TaskEligibilityResponse> getTaskEligibility(
            @PathVariable UUID taskId
    ) {

        TaskEligibilityResponse eligibility =
                taskEligibilityService.getTaskEligibility(taskId);

        return ResponseEntity.ok(eligibility);
    }
}