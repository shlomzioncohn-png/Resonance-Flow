package com.rnd.bff.controller;

import com.rnd.bff.dto.ConstraintDTO;
import com.rnd.bff.dto.ConstraintStatusUpdateDTO;
import com.rnd.bff.dto.DependencyGraphDTO;
import com.rnd.bff.dto.ManagerProjectDTO;
import com.rnd.bff.service.BottlenecksService;
import com.rnd.bff.service.ManagerKpisService;
import com.rnd.bff.service.TaskConstraintsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Manager Dashboard's all-projects grid, the Bottlenecks screen's dependency graph,
 * and the constraints of a task in the Bottlenecks screen's task drawer.
 *
 * Gateway forwards GET /api/manager/projects and GET /api/manager/bottlenecks/graph
 * here as GET /manager/projects and GET /manager/bottlenecks/graph.
 */
@RestController
@RequestMapping("/manager")
@Tag(name = "Manager", description = "Called only by the Gateway, never directly by the Frontend")
public class ManagerController {

    private static final Logger logger = LoggerFactory.getLogger(ManagerController.class);

    private final ManagerKpisService managerKpisService;
    private final BottlenecksService bottlenecksService;
    private final TaskConstraintsService taskConstraintsService;

    public ManagerController(ManagerKpisService managerKpisService, BottlenecksService bottlenecksService,
                             TaskConstraintsService taskConstraintsService) {
        this.managerKpisService = managerKpisService;
        this.bottlenecksService = bottlenecksService;
        this.taskConstraintsService = taskConstraintsService;
    }

    @Operation(summary = "List all projects",
            description = "Project grid with health/KPI data, counted from the tenant's Inbox tasks. Team, owner and members are placeholders until Inbox's project API is available.")
    @GetMapping("/projects")
    public ResponseEntity<List<ManagerProjectDTO>> getProjects(@RequestHeader("X-User-Id") String userId) {
        logger.info("Manager projects request - actorId: {}", userId);
        return ResponseEntity.ok(managerKpisService.getProjects(userId));
    }

    @Operation(summary = "Task dependency graph",
            description = "Chain's dependency graph for the user's tenant, with Inbox's task titles. "
                    + "projectId keeps only that project's tasks. 503 when Chain or Inbox is unavailable.")
    @GetMapping("/bottlenecks/graph")
    public ResponseEntity<DependencyGraphDTO> getDependencyGraph(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(required = false) String projectId) {
        logger.info("Bottlenecks graph request - actorId: {}, projectId: {}", userId, projectId);
        return ResponseEntity.ok(bottlenecksService.getGraph(userId, projectId));
    }

    @Operation(summary = "Task constraints",
            description = "Chain's constraints of one task in the user's tenant. "
                    + "404 when the task isn't in the tenant. 503 when Chain or Inbox is unavailable.")
    @GetMapping("/tasks/{taskId}/constraints")
    public ResponseEntity<List<ConstraintDTO>> getTaskConstraints(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String taskId) {
        logger.info("Task constraints request - actorId: {}, taskId: {}", userId, taskId);
        return ResponseEntity.ok(taskConstraintsService.getConstraints(userId, taskId));
    }

    @Operation(summary = "Update constraint status",
            description = "Sets a constraint to PENDING, SATISFIED or WAIVED in Chain. "
                    + "400 for any other status. 404 when the task isn't in the tenant or the constraint "
                    + "doesn't exist on it. 503 when Chain or Inbox is unavailable.")
    @PatchMapping("/tasks/{taskId}/constraints/{constraintId}")
    public ResponseEntity<ConstraintDTO> updateConstraintStatus(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String taskId,
            @PathVariable String constraintId,
            @Valid @RequestBody ConstraintStatusUpdateDTO body) {
        logger.info("Constraint status update - actorId: {}, taskId: {}, constraintId: {}, newStatus: {}",
                userId, taskId, constraintId, body.getStatus());
        return ResponseEntity.ok(
                taskConstraintsService.updateStatus(userId, taskId, constraintId, body.getStatus()));
    }
}
