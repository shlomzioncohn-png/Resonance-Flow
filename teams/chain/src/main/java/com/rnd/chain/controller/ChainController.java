package com.rnd.chain.controller;

import com.rnd.chain.dto.AddDependencyRequest;
import com.rnd.chain.dto.CreateConstraintRequest;
import com.rnd.chain.dto.UpdateConstraintStatusRequest;
import com.rnd.chain.dto.TaskEligibilityResponse;

import com.rnd.chain.model.Constraint;
import com.rnd.chain.model.Task;
import com.rnd.chain.model.TaskDependency;

import com.rnd.chain.service.ChainService;
import com.rnd.chain.service.ConstraintService;
import com.rnd.chain.service.DependencyGraphService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/tasks")
public class ChainController {

    private final ChainService chainService;
    private final DependencyGraphService dependencyGraphService;
    private final ConstraintService constraintService;

    public ChainController(
            ChainService chainService,
            DependencyGraphService dependencyGraphService,
            ConstraintService constraintService
    ) {
        this.chainService = chainService;
        this.dependencyGraphService = dependencyGraphService;
        this.constraintService = constraintService;
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
                chainService.addDependency(
                        taskId,
                        request.getDependsOnTaskId()
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(createdDependency);
    }


    // 2. Remove dependency
    @DeleteMapping("/{taskId}/dependencies/{dependsOnTaskId}")
    public ResponseEntity<Void> removeDependency(
            @PathVariable UUID taskId,
            @PathVariable UUID dependsOnTaskId
    ) {

        chainService.removeDependency(
                taskId,
                dependsOnTaskId
        );

        return ResponseEntity
                .noContent()
                .build();
    }


    // 3. Get direct dependencies
    @GetMapping("/{taskId}/dependencies")
    public ResponseEntity<List<TaskDependency>> getDependencies(
            @PathVariable UUID taskId
    ) {

        List<TaskDependency> dependencies =
                chainService.getDependencies(taskId);

        return ResponseEntity.ok(dependencies);
    }


    // 4. Get dependents
    @GetMapping("/{taskId}/dependents")
    public ResponseEntity<List<TaskDependency>> getDependents(
            @PathVariable UUID taskId
    ) {

        List<TaskDependency> dependents =
                chainService.getDependents(taskId);

        return ResponseEntity.ok(dependents);
    }


    // =========================================================
    // Stage 2 - Dependency Traversal
    // =========================================================


    // 5. Get direct blockers
    @GetMapping("/{taskId}/blockers")
    public ResponseEntity<List<Task>> getBlockers(
            @PathVariable UUID taskId
    ) {

        List<Task> blockers =
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
                        request
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
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
                        taskId,
                        constraintId,
                        request.getStatus()
                );

        return ResponseEntity.ok(updatedConstraint);
    }


    // =========================================================
    // Stage 4 - Task Eligibility
    // =========================================================

    @GetMapping("/{taskId}/eligibility")
    public ResponseEntity<TaskEligibilityResponse> getTaskEligibility(
            @PathVariable UUID taskId
    ) {

        TaskEligibilityResponse eligibility =
                chainService.getTaskEligibility(taskId);

        return ResponseEntity.ok(eligibility);
    }
}
