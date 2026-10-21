package com.rnd.inbox.task.controller;
import com.rnd.inbox.task.dto.TaskCreateRequestDto;
import com.rnd.inbox.task.dto.TaskFilterDto;
import com.rnd.inbox.task.dto.TaskResponseDto;
import com.rnd.inbox.task.dto.TaskStatusUpdateRequestDto;
import com.rnd.inbox.task.dto.TaskUpdateRequestDto;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.security.TaskAuthorization;
import com.rnd.inbox.task.service.TaskService;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.security.CurrentActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

// No @RequireRole on any endpoint here - every role can act on tasks, scoped only by
// TaskAuthorization (SYSTEM_ADMIN: everywhere; everyone else: their own tenant only). See the
// build-api skill's Q9: not every entity needs a coarse, role-only gate.
@Tag(name = "Tasks", description = "Create, update, and look up tasks")
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @Operation(summary = "Create a new task (manual entry)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Task created",
                    content = @Content(schema = @Schema(implementation = TaskResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403", description = "Tenant, project, or owner is outside the actor's own tenant"),
            @ApiResponse(responseCode = "404", description = "Tenant, project, or owner not found")
    })
    @PostMapping
    public ResponseEntity<TaskResponseDto> createTask(@Valid @RequestBody TaskCreateRequestDto request,
                                                        @CurrentActor UUID actorId,
                                                        UriComponentsBuilder uriBuilder) {
        TaskResponseDto created = taskService.createTaskFromRequest(request, actorId);
        return ResponseEntity
                .created(uriBuilder.path("/api/v1/tasks/{id}").build(created.getId()))
                .body(created);
    }

    @Operation(summary = "Get a task by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task found",
                    content = @Content(schema = @Schema(implementation = TaskResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403",
                    description = "Task is outside the actor's scope (SYSTEM_ADMIN: any tenant; everyone else: their own tenant only)"),
            @ApiResponse(responseCode = "404", description = "Task not found")
    })
    @GetMapping("/{id}")
    public TaskResponseDto getTaskById(@PathVariable UUID id, @CurrentActor UUID actorId) {
        return taskService.getTaskById(id, actorId);
    }

    @Operation(summary = "List tasks within the actor's scope, paginated, with optional filters "
            + "(SYSTEM_ADMIN: every tenant; everyone else: their own tenant only)",
            description = "Pass userId to get only the tasks related to that user: tasks they own, tasks "
                    + "assigned to them personally, or tasks assigned to a team they are currently a member of "
                    + "while no individual is responsible for them (no owner, no personal assignee). "
                    + "A userId outside the actor's scope returns an empty page. "
                    + "Sorting is supported by deadline, priorityScore and createdAt only "
                    + "(e.g. sort=deadline,asc); with no sort the newest tasks come first. "
                    + "The task id is always the final tie-breaker, so paging is stable.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of tasks",
                    content = @Content(schema = @Schema(implementation = PageResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "deadlineTo is before deadlineFrom, "
                    + "noDeadline combined with a deadline range, a filter value that does not parse "
                    + "(e.g. userId is not a UUID), or sorting by an unsupported property"),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity")
    })
    @GetMapping
    public PageResponseDto<TaskResponseDto> listTasks(
            TaskFilterDto filter, Pageable pageable, @CurrentActor UUID actorId) {
        return taskService.listTasks(filter, pageable, actorId);
    }

    @Operation(summary = "Update a task's details")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task updated",
                    content = @Content(schema = @Schema(implementation = TaskResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403",
                    description = "Task, project, or owner is outside the actor's own tenant"),
            @ApiResponse(responseCode = "404", description = "Task, project, or owner not found")
    })
    @PatchMapping("/{id}")
    public TaskResponseDto updateTask(@PathVariable UUID id, @Valid @RequestBody TaskUpdateRequestDto request,
                                       @CurrentActor UUID actorId) {
        return taskService.updateTaskFromRequest(id, request, actorId);
    }

    @Operation(summary = "Change a task's status (any lifecycle transition, not only completion)",
            description = "Feedback (feedbackText) is optional and accepted only together with status REJECTED.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status updated",
                    content = @Content(schema = @Schema(implementation = TaskResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "snoozedUntil missing while status is SNOOZED, "
                    + "or feedback sent with a status other than REJECTED"),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403", description = "Task is outside the actor's own tenant"),
            @ApiResponse(responseCode = "404", description = "Task not found")
    })
    @PatchMapping("/{id}/status")
    public TaskResponseDto updateStatus(@PathVariable UUID id,
                                         @Valid @RequestBody TaskStatusUpdateRequestDto request,
                                         @CurrentActor UUID actorId) {
        return taskService.updateTaskStatus(id, request, actorId);
    }
}
