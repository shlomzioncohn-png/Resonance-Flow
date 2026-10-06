package com.rnd.chain.controller;

import com.rnd.chain.dto.TaskResponse;
import com.rnd.chain.dto.TaskTraceResponse;
import com.rnd.chain.service.TaskTraversalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Graph traversal endpoints for Chain: same-tier dependency traversal, parent/child hierarchy
 * traversal, and cross-tier trace. Every route here is read-only (GET) - nothing in this
 * controller creates, modifies, or deletes a task or a dependency edge.
 *
 * Route naming mirrors TaskResponse/TaskTraceResponse's own field vocabulary: a bare path is
 * the direct/one-level relationship, "/transitive" is the full closure over that same relation.
 * Ancestors has no "/transitive" variant since there's only one parent per task - the ancestor
 * chain already is the transitive closure, and its first entry already is the direct parent.
 */
@RestController
@RequestMapping("/tasks")
public class ChainController {

    private final TaskTraversalService taskTraversalService;

    public ChainController(TaskTraversalService taskTraversalService) {
        this.taskTraversalService = taskTraversalService;
    }

    @GetMapping("/{id}/dependencies")
    public ResponseEntity<List<TaskResponse>> getDirectDependencies(@PathVariable UUID id) {
        return ResponseEntity.ok(taskTraversalService.getDirectDependencies(id));
    }

    @GetMapping("/{id}/dependencies/transitive")
    public ResponseEntity<List<TaskResponse>> getAllDependencies(@PathVariable UUID id) {
        return ResponseEntity.ok(taskTraversalService.getAllDependencies(id));
    }

    @GetMapping("/{id}/dependents")
    public ResponseEntity<List<TaskResponse>> getDirectDependents(@PathVariable UUID id) {
        return ResponseEntity.ok(taskTraversalService.getDirectDependents(id));
    }

    @GetMapping("/{id}/dependents/transitive")
    public ResponseEntity<List<TaskResponse>> getAllDependents(@PathVariable UUID id) {
        return ResponseEntity.ok(taskTraversalService.getAllDependents(id));
    }

    @GetMapping("/{id}/ancestors")
    public ResponseEntity<List<TaskResponse>> getAncestors(@PathVariable UUID id) {
        return ResponseEntity.ok(taskTraversalService.getAncestors(id));
    }

    @GetMapping("/{id}/children")
    public ResponseEntity<List<TaskResponse>> getDirectChildren(@PathVariable UUID id) {
        return ResponseEntity.ok(taskTraversalService.getDirectChildren(id));
    }

    @GetMapping("/{id}/descendants")
    public ResponseEntity<List<TaskResponse>> getAllDescendants(@PathVariable UUID id) {
        return ResponseEntity.ok(taskTraversalService.getAllDescendants(id));
    }

    @GetMapping("/{id}/trace")
    public ResponseEntity<TaskTraceResponse> getTrace(@PathVariable UUID id) {
        return ResponseEntity.ok(taskTraversalService.getTrace(id));
    }
}
