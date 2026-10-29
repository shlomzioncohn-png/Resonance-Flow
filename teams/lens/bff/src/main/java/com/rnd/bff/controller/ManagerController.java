package com.rnd.bff.controller;

import com.rnd.bff.dto.DependencyGraphDTO;
import com.rnd.bff.dto.ManagerProjectDTO;
import com.rnd.bff.service.BottlenecksService;
import com.rnd.bff.service.ManagerKpisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Manager Dashboard's all-projects grid and the Bottlenecks screen's dependency graph.
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

    public ManagerController(ManagerKpisService managerKpisService, BottlenecksService bottlenecksService) {
        this.managerKpisService = managerKpisService;
        this.bottlenecksService = bottlenecksService;
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
}
