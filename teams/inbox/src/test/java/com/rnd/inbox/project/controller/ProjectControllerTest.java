package com.rnd.inbox.project.controller;

import com.rnd.inbox.project.ProjectNotFoundException;
import com.rnd.inbox.project.ProjectStatus;
import com.rnd.inbox.project.dto.ProjectResponseDto;
import com.rnd.inbox.project.dto.ProjectSummaryResponseDto;
import com.rnd.inbox.project.dto.ProjectTaskCountsDto;
import com.rnd.inbox.project.exception.InvalidProjectDatesException;
import com.rnd.inbox.project.exception.InvalidProjectSortException;
import com.rnd.inbox.project.exception.ProjectHasTasksException;
import com.rnd.inbox.project.service.ProjectService;
import com.rnd.inbox.shared.config.AppConfig;
import com.rnd.inbox.shared.config.WebMvcConfig;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.tenant.dto.TenantSummaryDto;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.user.model.UserStatus;
import com.rnd.inbox.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Status codes, error codes and JSON field names below are the ones the Projects API Contract
// promises; the service is mocked, so this checks only the HTTP layer.
@WebMvcTest(ProjectController.class)
@Import({AppConfig.class, WebMvcConfig.class})
class ProjectControllerTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    private static final String VALID_CREATE_BODY = """
            {
              "tenant_id": "3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90",
              "name": "Checkout Revamp",
              "description": "Modernizing the checkout flow",
              "owner_id": "8b1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
              "team_id": "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f",
              "status": "ACTIVE",
              "start_date": "2026-11-01",
              "target_date": "2027-03-31"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProjectService projectService;

    @MockBean
    private UserRepository userRepository;

    private UUID projectId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        stubActorRole(UserRole.TENANT_ADMIN);
    }

    private void stubActorRole(UserRole role) {
        User actor = new User();
        actor.setId(actorId);
        actor.setRole(role);
        actor.setStatus(UserStatus.ACTIVE);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
    }

    private ProjectResponseDto sampleProject() {
        return new ProjectResponseDto(projectId,
                new TenantSummaryDto(UUID.randomUUID(), "Acme", "acme"),
                "Checkout Revamp", "Modernizing the checkout flow",
                UUID.randomUUID(), "Dana Cohen", UUID.randomUUID(), "Payments",
                ProjectStatus.ACTIVE, LocalDate.parse("2026-11-01"), LocalDate.parse("2027-03-31"),
                Instant.parse("2026-10-14T09:00:00Z"), Instant.parse("2026-10-14T09:00:00Z"));
    }

    // ---- POST /api/v1/projects ----

    @Test
    void createProject_withValidRequest_returns201WithLocationHeaderAndSnakeCaseBody() throws Exception {
        when(projectService.createProject(any(), eq(actorId))).thenReturn(sampleProject());

        mockMvc.perform(post("/api/v1/projects")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/projects/" + projectId)))
                .andExpect(jsonPath("$.id").value(projectId.toString()))
                .andExpect(jsonPath("$.tenant.slug").value("acme"))
                .andExpect(jsonPath("$.owner_display_name").value("Dana Cohen"))
                .andExpect(jsonPath("$.team_name").value("Payments"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.start_date").value("2026-11-01"))
                .andExpect(jsonPath("$.target_date").value("2027-03-31"))
                .andExpect(jsonPath("$.created_at").value("2026-10-14T09:00:00Z"));
    }

    @Test
    void createProject_whenActorIsTeamLead_returns201() throws Exception {
        stubActorRole(UserRole.TEAM_LEAD);
        when(projectService.createProject(any(), eq(actorId))).thenReturn(sampleProject());

        mockMvc.perform(post("/api/v1/projects")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void createProject_whenActorIsEmployee_returns403WithoutCallingService() throws Exception {
        stubActorRole(UserRole.EMPLOYEE);

        mockMvc.perform(post("/api/v1/projects")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(projectService, never()).createProject(any(), any());
    }

    @Test
    void createProject_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void createProject_withMissingRequiredFields_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.field_errors[?(@.field == 'tenant_id')]").exists())
                .andExpect(jsonPath("$.field_errors[?(@.field == 'name')]").exists());

        verify(projectService, never()).createProject(any(), any());
    }

    @Test
    void createProject_withNameLongerThan255Characters_returns400() throws Exception {
        String body = """
                {"tenant_id": "3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90", "name": "%s"}
                """.formatted("x".repeat(256));

        mockMvc.perform(post("/api/v1/projects")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void createProject_withUnknownStatus_returns400() throws Exception {
        String body = """
                {"tenant_id": "3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90", "name": "X", "status": "DONE"}
                """;

        mockMvc.perform(post("/api/v1/projects")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.field_errors[0].field").value("status"));
    }

    @Test
    void createProject_withMalformedOwnerId_returns400() throws Exception {
        String body = """
                {"tenant_id": "3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90", "name": "X", "owner_id": "not-a-uuid"}
                """;

        mockMvc.perform(post("/api/v1/projects")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.field_errors[0].field").value("owner_id"));
    }

    @Test
    void createProject_whenTargetDateBeforeStartDate_returns400() throws Exception {
        when(projectService.createProject(any(), eq(actorId))).thenThrow(
                new InvalidProjectDatesException(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-10-01")));

        mockMvc.perform(post("/api/v1/projects")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PROJECT_DATES"));
    }

    @Test
    void createProject_whenOwnerOutsideActorsTenant_returns403() throws Exception {
        when(projectService.createProject(any(), eq(actorId)))
                .thenThrow(new ForbiddenActionException("User does not belong to tenant"));

        mockMvc.perform(post("/api/v1/projects")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    // ---- GET /api/v1/projects/{id} ----

    @Test
    void getProjectById_whenFound_returns200() throws Exception {
        when(projectService.getProjectById(projectId, actorId)).thenReturn(sampleProject());

        mockMvc.perform(get("/api/v1/projects/{id}", projectId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId.toString()))
                .andExpect(jsonPath("$.name").value("Checkout Revamp"));
    }

    @Test
    void getProjectById_whenActorIsEmployee_returns200() throws Exception {
        stubActorRole(UserRole.EMPLOYEE);
        when(projectService.getProjectById(projectId, actorId)).thenReturn(sampleProject());

        mockMvc.perform(get("/api/v1/projects/{id}", projectId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void getProjectById_whenNotFound_returns404() throws Exception {
        when(projectService.getProjectById(projectId, actorId)).thenThrow(new ProjectNotFoundException(projectId));

        mockMvc.perform(get("/api/v1/projects/{id}", projectId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void getProjectById_whenProjectInAnotherTenant_returns403() throws Exception {
        when(projectService.getProjectById(projectId, actorId))
                .thenThrow(new ForbiddenActionException("Actor cannot act on " + projectId));

        mockMvc.perform(get("/api/v1/projects/{id}", projectId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void getProjectById_withMalformedId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/projects/not-a-uuid").header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // ---- GET /api/v1/projects ----

    @Test
    void listProjects_returns200WithFlatPageResponse() throws Exception {
        when(projectService.listProjects(any(), any(), eq(actorId)))
                .thenReturn(new PageResponseDto<>(List.of(sampleProject()), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/projects").header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(projectId.toString()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total_elements").value(1))
                .andExpect(jsonPath("$.total_pages").value(1));
    }

    @Test
    void listProjects_withInvalidStatusFilter_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/projects?status=NOT_A_STATUS").header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isBadRequest());

        verify(projectService, never()).listProjects(any(), any(), any());
    }

    @Test
    void listProjects_withMalformedTeamId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/projects?teamId=not-a-uuid").header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listProjects_withUnsupportedSort_returns400() throws Exception {
        when(projectService.listProjects(any(), any(), eq(actorId)))
                .thenThrow(new InvalidProjectSortException("description", List.of("name", "targetDate", "createdAt")));

        mockMvc.perform(get("/api/v1/projects?sort=description,asc").header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PROJECT_SORT"));
    }

    @Test
    void listProjects_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    // ---- GET /api/v1/projects/summary ----

    @Test
    void listProjectSummaries_returns200WithTaskCountsInSnakeCase() throws Exception {
        ProjectSummaryResponseDto summary = new ProjectSummaryResponseDto(projectId, "Checkout Revamp",
                ProjectStatus.ACTIVE, UUID.randomUUID(), "Payments", UUID.randomUUID(), "Dana Cohen",
                LocalDate.parse("2027-03-31"), new ProjectTaskCountsDto(40, 10, 12, 2, 14, 2, 3), 37,
                Instant.parse("2026-10-13T16:20:00Z"));
        when(projectService.listProjectSummaries(any(), any(), eq(actorId)))
                .thenReturn(new PageResponseDto<>(List.of(summary), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/projects/summary").header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].project_id").value(projectId.toString()))
                .andExpect(jsonPath("$.content[0].task_counts.total").value(40))
                .andExpect(jsonPath("$.content[0].task_counts.in_progress").value(12))
                .andExpect(jsonPath("$.content[0].task_counts.closed_without_done").value(2))
                .andExpect(jsonPath("$.content[0].task_counts.overdue").value(3))
                .andExpect(jsonPath("$.content[0].progress_percent").value(37))
                .andExpect(jsonPath("$.content[0].last_activity_at").value("2026-10-13T16:20:00Z"))
                .andExpect(jsonPath("$.total_elements").value(1));

        verify(projectService, never()).getProjectById(any(), any());
    }

    @Test
    void listProjectSummaries_whenActorIsEmployee_returns200() throws Exception {
        stubActorRole(UserRole.EMPLOYEE);
        when(projectService.listProjectSummaries(any(), any(), eq(actorId)))
                .thenReturn(new PageResponseDto<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/projects/summary").header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isOk());
    }

    // ---- PATCH /api/v1/projects/{id} ----

    @Test
    void updateProject_withValidRequest_returns200() throws Exception {
        when(projectService.updateProject(eq(projectId), any(), eq(actorId))).thenReturn(sampleProject());

        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Checkout Revamp v2", "status": "COMPLETED", "team_id": null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId.toString()));
    }

    @Test
    void updateProject_withBlankName_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        verify(projectService, never()).updateProject(any(), any(), any());
    }

    @Test
    void updateProject_withUnknownStatus_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"FINISHED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void updateProject_whenActorIsEmployee_returns403WithoutCallingService() throws Exception {
        stubActorRole(UserRole.EMPLOYEE);

        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"New name\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(projectService, never()).updateProject(any(), any(), any());
    }

    @Test
    void updateProject_whenNotFound_returns404() throws Exception {
        when(projectService.updateProject(eq(projectId), any(), eq(actorId)))
                .thenThrow(new ProjectNotFoundException(projectId));

        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"New name\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PROJECT_NOT_FOUND"));
    }

    // ---- DELETE /api/v1/projects/{id} ----

    @Test
    void deleteProject_whenProjectHasNoTasks_returns204WithEmptyBody() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/{id}", projectId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(projectService).deleteProject(projectId, actorId);
    }

    @Test
    void deleteProject_whenProjectHasTasks_returns409() throws Exception {
        doThrow(new ProjectHasTasksException(projectId, 12)).when(projectService).deleteProject(projectId, actorId);

        mockMvc.perform(delete("/api/v1/projects/{id}", projectId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("PROJECT_HAS_TASKS"))
                .andExpect(jsonPath("$.message").value(
                        "Project " + projectId + " has 12 tasks and cannot be deleted"));
    }

    @Test
    void deleteProject_whenActorIsEmployee_returns403WithoutCallingService() throws Exception {
        stubActorRole(UserRole.EMPLOYEE);

        mockMvc.perform(delete("/api/v1/projects/{id}", projectId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(projectService, never()).deleteProject(any(), any());
    }

    @Test
    void deleteProject_whenNotFound_returns404() throws Exception {
        doThrow(new ProjectNotFoundException(projectId)).when(projectService).deleteProject(projectId, actorId);

        mockMvc.perform(delete("/api/v1/projects/{id}", projectId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PROJECT_NOT_FOUND"));
    }
}
