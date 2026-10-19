package com.rnd.inbox.project.controller;

import com.rnd.inbox.project.dto.ProjectCreateRequestDto;
import com.rnd.inbox.project.dto.ProjectFilterDto;
import com.rnd.inbox.project.dto.ProjectResponseDto;
import com.rnd.inbox.project.dto.ProjectSummaryResponseDto;
import com.rnd.inbox.project.dto.ProjectUpdateRequestDto;
import com.rnd.inbox.project.service.ProjectService;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.security.CurrentActor;
import com.rnd.inbox.shared.security.RequireRole;
import com.rnd.inbox.user.model.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

@Tag(name = "Projects", description = "Create, update, delete and look up projects")
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @Operation(summary = "Create a new project (not EMPLOYEE)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Project created",
                    content = @Content(schema = @Schema(implementation = ProjectResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request body, or target_date before start_date"),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403",
                    description = "Actor is an EMPLOYEE, or the tenant, owner or team is outside the actor's own tenant"),
            @ApiResponse(responseCode = "404", description = "Tenant, owner or team not found")
    })
    @PostMapping
    @RequireRole({UserRole.SYSTEM_ADMIN, UserRole.TENANT_ADMIN, UserRole.TEAM_LEAD})
    public ResponseEntity<ProjectResponseDto> createProject(@Valid @RequestBody ProjectCreateRequestDto request,
                                                            @CurrentActor UUID actorId,
                                                            UriComponentsBuilder uriBuilder) {
        ProjectResponseDto created = projectService.createProject(request, actorId);
        return ResponseEntity
                .created(uriBuilder.path("/api/v1/projects/{id}").build(created.getId()))
                .body(created);
    }

    @Operation(summary = "List projects with task counts and progress, paginated, with optional filters",
            description = "Same filters, sorting and scope as GET /api/v1/projects. Counts are computed "
                    + "from the project's tasks on every call.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of project summaries",
                    content = @Content(schema = @Schema(implementation = PageResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "A filter value that does not parse, or an unsupported sort"),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity")
    })
    @GetMapping("/summary")
    public PageResponseDto<ProjectSummaryResponseDto> listProjectSummaries(
            ProjectFilterDto filter, Pageable pageable, @CurrentActor UUID actorId) {
        return projectService.listProjectSummaries(filter, pageable, actorId);
    }

    @Operation(summary = "Get a project by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Project found",
                    content = @Content(schema = @Schema(implementation = ProjectResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403",
                    description = "Project is outside the actor's scope (SYSTEM_ADMIN: any tenant; everyone else: their own tenant only)"),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    @GetMapping("/{id}")
    public ProjectResponseDto getProjectById(@PathVariable UUID id, @CurrentActor UUID actorId) {
        return projectService.getProjectById(id, actorId);
    }

    @Operation(summary = "List projects within the actor's scope, paginated, with optional filters "
            + "(SYSTEM_ADMIN: every tenant; everyone else: their own tenant only)",
            description = "Filters (search, status, teamId, ownerId) are combined with AND. Sorting is "
                    + "supported by name, targetDate and createdAt only; with no sort the newest projects "
                    + "come first. The project id is always the final tie-breaker, so paging is stable.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of projects",
                    content = @Content(schema = @Schema(implementation = PageResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "A filter value that does not parse, or an unsupported sort"),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity")
    })
    @GetMapping
    public PageResponseDto<ProjectResponseDto> listProjects(
            ProjectFilterDto filter, Pageable pageable, @CurrentActor UUID actorId) {
        return projectService.listProjects(filter, pageable, actorId);
    }

    @Operation(summary = "Update a project's details, including its status (not EMPLOYEE)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Project updated",
                    content = @Content(schema = @Schema(implementation = ProjectResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request body, or target_date before start_date"),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403",
                    description = "Actor is an EMPLOYEE, or the project, owner or team is outside the actor's own tenant"),
            @ApiResponse(responseCode = "404", description = "Project, owner or team not found")
    })
    @PatchMapping("/{id}")
    @RequireRole({UserRole.SYSTEM_ADMIN, UserRole.TENANT_ADMIN, UserRole.TEAM_LEAD})
    public ProjectResponseDto updateProject(@PathVariable UUID id,
                                            @Valid @RequestBody ProjectUpdateRequestDto request,
                                            @CurrentActor UUID actorId) {
        return projectService.updateProject(id, request, actorId);
    }

    @Operation(summary = "Delete a project that has no tasks (not EMPLOYEE)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Project deleted"),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403",
                    description = "Actor is an EMPLOYEE, or the project is outside the actor's own tenant"),
            @ApiResponse(responseCode = "404", description = "Project not found"),
            @ApiResponse(responseCode = "409", description = "Project still has tasks")
    })
    @DeleteMapping("/{id}")
    @RequireRole({UserRole.SYSTEM_ADMIN, UserRole.TENANT_ADMIN, UserRole.TEAM_LEAD})
    public ResponseEntity<Void> deleteProject(@PathVariable UUID id, @CurrentActor UUID actorId) {
        projectService.deleteProject(id, actorId);
        return ResponseEntity.noContent().build();
    }
}
