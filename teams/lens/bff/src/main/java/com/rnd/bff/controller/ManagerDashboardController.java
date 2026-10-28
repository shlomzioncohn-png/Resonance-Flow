package com.rnd.bff.controller;

import com.rnd.bff.dto.ManagerKpisDTO;
import com.rnd.bff.dto.ProjectTaskCountsDTO;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.service.ManagerKpisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
 * Manager Dashboard (/manager) data. Gateway forwards GET
 * /api/manager/dashboard/kpis here as GET /manager/dashboard/kpis, with the
 * caller's identity in X-User-Id. The tenant is never a parameter: Inbox
 * scopes the tasks to the acting user's own tenant.
 */
@RestController
@RequestMapping("/manager/dashboard")
@Tag(name = "Manager Dashboard", description = "Called only by the Gateway, never directly by the Frontend")
public class ManagerDashboardController {

    private static final Logger logger = LoggerFactory.getLogger(ManagerDashboardController.class);

    private final ManagerKpisService managerKpisService;

    public ManagerDashboardController(ManagerKpisService managerKpisService) {
        this.managerKpisService = managerKpisService;
    }

    @Operation(summary = "KPI tiles",
            description = "In progress, blocked and delayed task counts for the manager's tenant; avgBlockedDays is null until Chain provides it. 400 for an unknown period.")
    @GetMapping("/kpis")
    public ResponseEntity<ManagerKpisDTO> getKpis(
            @Parameter(description = "7d, 30d or 90d. Accepted but not applied yet - every KPI is a count of right now.")
            @RequestParam(required = false) String period,
            @Parameter(description = "Inbox project id; omit for the whole tenant")
            @RequestParam(required = false) String project,
            @RequestHeader("X-User-Id") String userId) {

        logger.info("Manager KPIs request - actorId: {}, period: {}, project: {}", userId, period, project);
        return ResponseEntity.ok(managerKpisService.getKpis(userId, period, project));
    }

    @Operation(summary = "Task counts per project",
            description = "The tenant's tasks per project by state (in progress, snoozed, open, done) plus how many are overdue, A-Z. REJECTED and CANCELLED tasks aren't counted.")
    @GetMapping("/projects")
    public ResponseEntity<List<ProjectTaskCountsDTO>> getProjectTaskCounts(
            @RequestHeader("X-User-Id") String userId) {

        logger.info("Manager project task counts request - actorId: {}", userId);
        return ResponseEntity.ok(managerKpisService.getProjectTaskCounts(userId));
    }

    @Operation(summary = "Tasks in progress",
            description = "The tenant's IN_PROGRESS tasks, soonest deadline first - the Work in progress screen.")
    @GetMapping("/tasks/in-progress")
    public ResponseEntity<List<TaskDTO>> getTasksInProgress(@RequestHeader("X-User-Id") String userId) {
        logger.info("Manager tasks in progress request - actorId: {}", userId);
        return ResponseEntity.ok(managerKpisService.getTasks(userId, ManagerKpisService.TaskView.IN_PROGRESS));
    }

    @Operation(summary = "Blocked and delayed tasks",
            description = "The tenant's open tasks that are blocked (is_available=false) or past their deadline, soonest deadline first - the Blocked & delayed screen.")
    @GetMapping("/tasks/blocked-delayed")
    public ResponseEntity<List<TaskDTO>> getTasksBlockedOrDelayed(@RequestHeader("X-User-Id") String userId) {
        logger.info("Manager blocked/delayed tasks request - actorId: {}", userId);
        return ResponseEntity.ok(managerKpisService.getTasks(userId, ManagerKpisService.TaskView.BLOCKED_OR_DELAYED));
    }
}
