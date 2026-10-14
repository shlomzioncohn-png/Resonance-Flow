package com.rnd.bff.controller;

import com.rnd.bff.client.ManagerClient;
import com.rnd.bff.dto.ManagerProjectDTO;
import com.rnd.bff.dto.WorkloadEntryDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Manager Dashboard's team-workload breakdown and all-projects grid.
 *
 * Gateway forwards GET /api/manager/workload and GET /api/manager/projects
 * here as GET /manager/workload and GET /manager/projects.
 */
@RestController
@RequestMapping("/manager")
@Tag(name = "Manager", description = "Called only by the Gateway, never directly by the Frontend")
public class ManagerController {

    private static final Logger logger = LoggerFactory.getLogger(ManagerController.class);

    private final ManagerClient managerClient;

    public ManagerController(ManagerClient managerClient) {
        this.managerClient = managerClient;
    }

    @Operation(summary = "Get team workload", description = "Active-task count per employee.")
    @GetMapping("/workload")
    public ResponseEntity<List<WorkloadEntryDTO>> getWorkload() {
        logger.info("Manager workload request");
        return ResponseEntity.ok(managerClient.getWorkload());
    }

    @Operation(summary = "List all projects", description = "Project grid with health/KPI data for the Manager Dashboard.")
    @GetMapping("/projects")
    public ResponseEntity<List<ManagerProjectDTO>> getProjects() {
        logger.info("Manager projects request");
        return ResponseEntity.ok(managerClient.getProjects());
    }
}
