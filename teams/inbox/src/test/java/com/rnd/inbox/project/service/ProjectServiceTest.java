package com.rnd.inbox.project.service;

import com.rnd.inbox.audit.AuditService;
import com.rnd.inbox.project.Project;
import com.rnd.inbox.project.ProjectNotFoundException;
import com.rnd.inbox.project.ProjectRepository;
import com.rnd.inbox.project.ProjectStatus;
import com.rnd.inbox.project.ProjectTaskStats;
import com.rnd.inbox.project.dto.ProjectCreateRequestDto;
import com.rnd.inbox.project.dto.ProjectFilterDto;
import com.rnd.inbox.project.dto.ProjectResponseDto;
import com.rnd.inbox.project.dto.ProjectSummaryResponseDto;
import com.rnd.inbox.project.dto.ProjectUpdateRequestDto;
import com.rnd.inbox.project.exception.InvalidProjectDatesException;
import com.rnd.inbox.project.exception.InvalidProjectSortException;
import com.rnd.inbox.project.exception.ProjectHasTasksException;
import com.rnd.inbox.project.mapper.ProjectMapper;
import com.rnd.inbox.project.security.ProjectAuthorization;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.team.exception.TeamNotFoundException;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.team.repository.TeamRepository;
import com.rnd.inbox.tenant.exception.TenantNotFoundException;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.tenant.repository.TenantRepository;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Expected behavior comes from the Projects API Contract (BR-PROJ-xxx), not from the implementation.
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Instant PROJECT_UPDATED_AT = Instant.parse("2026-10-01T00:00:00Z");

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private AuditService auditService;

    private ProjectService projectService;

    private Tenant tenant;
    private Tenant otherTenant;
    private User actor;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        projectService = new ProjectService(projectRepository, tenantRepository, userRepository, teamRepository,
                new ProjectMapper(clock), new ProjectAuthorization(), auditService, clock);

        tenant = tenant();
        otherTenant = tenant();
        actor = userIn(tenant, UserRole.TENANT_ADMIN);
        // lenient: some tests act as a different user (system admin, employee, unknown actor).
        lenient().when(userRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
    }

    private static Tenant tenant() {
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setName("Acme");
        t.setSlug("acme");
        return t;
    }

    private static User userIn(Tenant tenant, UserRole role) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTenant(tenant);
        user.setRole(role);
        user.setDisplayName("Dana Cohen");
        return user;
    }

    private static Team teamIn(Tenant tenant) {
        Team team = new Team();
        team.setId(UUID.randomUUID());
        team.setTenant(tenant);
        team.setName("Payments");
        return team;
    }

    private static Project projectIn(Tenant tenant) {
        Project project = new Project();
        project.setId(UUID.randomUUID());
        project.setTenant(tenant);
        project.setName("Checkout Revamp");
        project.setStatus(ProjectStatus.ACTIVE);
        project.setCreatedAt(PROJECT_UPDATED_AT);
        project.setUpdatedAt(PROJECT_UPDATED_AT);
        return project;
    }

    private ProjectCreateRequestDto createRequest() {
        ProjectCreateRequestDto request = new ProjectCreateRequestDto();
        request.setTenantId(tenant.getId().toString());
        request.setName("Checkout Revamp");
        return request;
    }

    private void stubSaveReturnsArgument() {
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            if (project.getId() == null) {
                project.setId(UUID.randomUUID());
            }
            return project;
        });
    }

    @SuppressWarnings("unchecked")
    private void stubFilteredPage(List<Project> projects) {
        when(projectRepository.findFiltered(anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(),
                anyBoolean(), any(), anyBoolean(), any(), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(projects, invocation.getArgument(10, Pageable.class),
                        projects.size()));
    }

    private void stubStats(List<ProjectTaskStats> stats) {
        when(projectRepository.findTaskStatsByProjectIds(anyCollection(), anyCollection(), any(), any(), any(),
                anyCollection(), anyCollection(), any())).thenReturn(stats);
    }

    private static ProjectTaskStats stats(UUID projectId, long total, long open, long inProgress, long snoozed,
                                          long done, long closedWithoutDone, long overdue, Instant lastTaskUpdatedAt) {
        return new ProjectTaskStats() {
            public UUID getProjectId() { return projectId; }
            public long getTotal() { return total; }
            public long getOpen() { return open; }
            public long getInProgress() { return inProgress; }
            public long getSnoozed() { return snoozed; }
            public long getDone() { return done; }
            public long getClosedWithoutDone() { return closedWithoutDone; }
            public long getOverdue() { return overdue; }
            public Instant getLastTaskUpdatedAt() { return lastTaskUpdatedAt; }
        };
    }

    // ---- create ----

    @Test
    void createProject_whenStatusOmitted_createsProjectAsPlanned() {
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        stubSaveReturnsArgument();

        ProjectResponseDto created = projectService.createProject(createRequest(), actor.getId());

        assertThat(created.getStatus()).isEqualTo(ProjectStatus.PLANNED);
    }

    @Test
    void createProject_whenStatusSent_createsProjectWithThatStatus() {
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        stubSaveReturnsArgument();
        ProjectCreateRequestDto request = createRequest();
        request.setStatus("ACTIVE");

        ProjectResponseDto created = projectService.createProject(request, actor.getId());

        assertThat(created.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
    }

    @Test
    void createProject_withOwnerAndTeam_returnsTheirIdsAndNames() {
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        User owner = userIn(tenant, UserRole.EMPLOYEE);
        Team team = teamIn(tenant);
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(teamRepository.findById(team.getId())).thenReturn(Optional.of(team));
        stubSaveReturnsArgument();
        ProjectCreateRequestDto request = createRequest();
        request.setOwnerId(owner.getId().toString());
        request.setTeamId(team.getId().toString());

        ProjectResponseDto created = projectService.createProject(request, actor.getId());

        assertThat(created.getOwnerId()).isEqualTo(owner.getId());
        assertThat(created.getOwnerDisplayName()).isEqualTo("Dana Cohen");
        assertThat(created.getTeamId()).isEqualTo(team.getId());
        assertThat(created.getTeamName()).isEqualTo("Payments");
        assertThat(created.getTenant().getId()).isEqualTo(tenant.getId());
    }

    @Test
    void createProject_auditsTheCreation() {
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        stubSaveReturnsArgument();

        ProjectResponseDto created = projectService.createProject(createRequest(), actor.getId());

        verify(auditService).createAuditLog(eq("PROJECT_CREATED"), eq("Project"), eq(created.getId()), eq(tenant),
                eq(actor), any(), isNull());
    }

    @Test
    void createProject_whenActorDoesNotExist_throwsUnauthenticatedException() {
        UUID unknownActor = UUID.randomUUID();
        when(userRepository.findById(unknownActor)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.createProject(createRequest(), unknownActor))
                .isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void createProject_whenTenantDoesNotExist_throwsTenantNotFoundException() {
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.createProject(createRequest(), actor.getId()))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void createProject_whenTenantIsNotTheActorsOwn_throwsForbiddenActionException() {
        when(tenantRepository.findById(otherTenant.getId())).thenReturn(Optional.of(otherTenant));
        ProjectCreateRequestDto request = createRequest();
        request.setTenantId(otherTenant.getId().toString());

        assertThatThrownBy(() -> projectService.createProject(request, actor.getId()))
                .isInstanceOf(ForbiddenActionException.class);
        verify(projectRepository, never()).save(any());
    }

    @Test
    void createProject_whenActorIsSystemAdmin_canCreateInAnyTenant() {
        User systemAdmin = userIn(otherTenant, UserRole.SYSTEM_ADMIN);
        when(userRepository.findById(systemAdmin.getId())).thenReturn(Optional.of(systemAdmin));
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        stubSaveReturnsArgument();

        ProjectResponseDto created = projectService.createProject(createRequest(), systemAdmin.getId());

        assertThat(created.getTenant().getId()).isEqualTo(tenant.getId());
    }

    @Test
    void createProject_whenOwnerIsInAnotherTenant_throwsForbiddenActionException() {
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        User foreignOwner = userIn(otherTenant, UserRole.EMPLOYEE);
        when(userRepository.findById(foreignOwner.getId())).thenReturn(Optional.of(foreignOwner));
        ProjectCreateRequestDto request = createRequest();
        request.setOwnerId(foreignOwner.getId().toString());

        assertThatThrownBy(() -> projectService.createProject(request, actor.getId()))
                .isInstanceOf(ForbiddenActionException.class);
        verify(projectRepository, never()).save(any());
    }

    @Test
    void createProject_whenTeamIsInAnotherTenant_throwsForbiddenActionException() {
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        Team foreignTeam = teamIn(otherTenant);
        when(teamRepository.findById(foreignTeam.getId())).thenReturn(Optional.of(foreignTeam));
        ProjectCreateRequestDto request = createRequest();
        request.setTeamId(foreignTeam.getId().toString());

        assertThatThrownBy(() -> projectService.createProject(request, actor.getId()))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void createProject_whenTeamDoesNotExist_throwsTeamNotFoundException() {
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        UUID missingTeam = UUID.randomUUID();
        when(teamRepository.findById(missingTeam)).thenReturn(Optional.empty());
        ProjectCreateRequestDto request = createRequest();
        request.setTeamId(missingTeam.toString());

        assertThatThrownBy(() -> projectService.createProject(request, actor.getId()))
                .isInstanceOf(TeamNotFoundException.class);
    }

    @Test
    void createProject_whenTargetDateBeforeStartDate_throwsInvalidProjectDatesException() {
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        ProjectCreateRequestDto request = createRequest();
        request.setStartDate(LocalDate.parse("2026-11-01"));
        request.setTargetDate(LocalDate.parse("2026-10-31"));

        assertThatThrownBy(() -> projectService.createProject(request, actor.getId()))
                .isInstanceOf(InvalidProjectDatesException.class);
        verify(projectRepository, never()).save(any());
    }

    @Test
    void createProject_whenTargetDateEqualsStartDate_createsProject() {
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        stubSaveReturnsArgument();
        ProjectCreateRequestDto request = createRequest();
        request.setStartDate(LocalDate.parse("2026-11-01"));
        request.setTargetDate(LocalDate.parse("2026-11-01"));

        ProjectResponseDto created = projectService.createProject(request, actor.getId());

        assertThat(created.getTargetDate()).isEqualTo(LocalDate.parse("2026-11-01"));
    }

    // ---- get ----

    @Test
    void getProjectById_whenProjectIsInTheActorsTenant_returnsIt() {
        Project project = projectIn(tenant);
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));

        ProjectResponseDto found = projectService.getProjectById(project.getId(), actor.getId());

        assertThat(found.getId()).isEqualTo(project.getId());
        assertThat(found.getName()).isEqualTo("Checkout Revamp");
    }

    @Test
    void getProjectById_whenProjectIsInAnotherTenant_throwsForbiddenActionException() {
        Project project = projectIn(otherTenant);
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> projectService.getProjectById(project.getId(), actor.getId()))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void getProjectById_whenNotFound_throwsProjectNotFoundException() {
        UUID missing = UUID.randomUUID();
        when(projectRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.getProjectById(missing, actor.getId()))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    // ---- list ----

    @Test
    void listProjects_whenActorIsEmployee_isLimitedToTheirOwnTenant() {
        User employee = userIn(tenant, UserRole.EMPLOYEE);
        when(userRepository.findById(employee.getId())).thenReturn(Optional.of(employee));
        stubFilteredPage(List.of(projectIn(tenant)));

        projectService.listProjects(new ProjectFilterDto(), PageRequest.of(0, 20), employee.getId());

        verify(projectRepository).findFiltered(eq(false), eq(tenant.getId()), anyBoolean(), any(), anyBoolean(),
                any(), anyBoolean(), any(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    void listProjects_whenActorIsSystemAdmin_isNotLimitedToATenant() {
        User systemAdmin = userIn(tenant, UserRole.SYSTEM_ADMIN);
        when(userRepository.findById(systemAdmin.getId())).thenReturn(Optional.of(systemAdmin));
        stubFilteredPage(List.of());

        projectService.listProjects(new ProjectFilterDto(), PageRequest.of(0, 20), systemAdmin.getId());

        verify(projectRepository).findFiltered(eq(true), isNull(), anyBoolean(), any(), anyBoolean(), any(),
                anyBoolean(), any(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    void listProjects_passesEveryFilterThrough() {
        stubFilteredPage(List.of());
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        ProjectFilterDto filter = new ProjectFilterDto();
        filter.setSearch("CheckOut");
        filter.setStatus(ProjectStatus.ACTIVE);
        filter.setTeamId(teamId);
        filter.setOwnerId(ownerId);

        projectService.listProjects(filter, PageRequest.of(0, 20), actor.getId());

        verify(projectRepository).findFiltered(eq(false), eq(tenant.getId()), eq(true), eq("%checkout%"),
                eq(true), eq(ProjectStatus.ACTIVE), eq(true), eq(teamId), eq(true), eq(ownerId),
                any(Pageable.class));
    }

    @Test
    void listProjects_withoutSort_returnsNewestFirstWithIdAsTieBreaker() {
        stubFilteredPage(List.of());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

        projectService.listProjects(new ProjectFilterDto(), PageRequest.of(0, 20), actor.getId());

        verify(projectRepository).findFiltered(anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(),
                anyBoolean(), any(), anyBoolean(), any(), pageable.capture());
        assertThat(pageable.getValue().getSort()).containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    @Test
    void listProjects_sortedByName_keepsIdAsTheLastSortKey() {
        stubFilteredPage(List.of());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

        projectService.listProjects(new ProjectFilterDto(), PageRequest.of(0, 20, Sort.by(Sort.Order.asc("name"))),
                actor.getId());

        verify(projectRepository).findFiltered(anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(),
                anyBoolean(), any(), anyBoolean(), any(), pageable.capture());
        assertThat(pageable.getValue().getSort()).containsExactly(Sort.Order.asc("name"), Sort.Order.asc("id"));
    }

    @Test
    void listProjects_sortedByUnsupportedProperty_throwsInvalidProjectSortException() {
        Pageable bySomethingElse = PageRequest.of(0, 20, Sort.by("description"));

        assertThatThrownBy(() -> projectService.listProjects(new ProjectFilterDto(), bySomethingElse, actor.getId()))
                .isInstanceOf(InvalidProjectSortException.class);
    }

    // ---- update ----

    @Test
    void updateProject_whenFieldsOmitted_leavesThemUnchanged() {
        Project project = projectIn(tenant);
        project.setDescription("Keep me");
        project.setTargetDate(LocalDate.parse("2027-03-31"));
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        stubSaveReturnsArgument();
        ProjectUpdateRequestDto request = new ProjectUpdateRequestDto();
        request.setName("Checkout Revamp v2");

        ProjectResponseDto updated = projectService.updateProject(project.getId(), request, actor.getId());

        assertThat(updated.getName()).isEqualTo("Checkout Revamp v2");
        assertThat(updated.getDescription()).isEqualTo("Keep me");
        assertThat(updated.getTargetDate()).isEqualTo(LocalDate.parse("2027-03-31"));
        assertThat(updated.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
    }

    @Test
    void updateProject_whenTeamIdSentAsNull_clearsTheTeam() {
        Project project = projectIn(tenant);
        project.setTeam(teamIn(tenant));
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        stubSaveReturnsArgument();
        ProjectUpdateRequestDto request = new ProjectUpdateRequestDto();
        request.setTeamId(JsonNullable.of(null));

        ProjectResponseDto updated = projectService.updateProject(project.getId(), request, actor.getId());

        assertThat(updated.getTeamId()).isNull();
        assertThat(updated.getTeamName()).isNull();
    }

    @Test
    void updateProject_changesStatusToAnyOtherStatus() {
        Project project = projectIn(tenant);
        project.setStatus(ProjectStatus.COMPLETED);
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        stubSaveReturnsArgument();
        ProjectUpdateRequestDto request = new ProjectUpdateRequestDto();
        request.setStatus("PLANNED");

        ProjectResponseDto updated = projectService.updateProject(project.getId(), request, actor.getId());

        assertThat(updated.getStatus()).isEqualTo(ProjectStatus.PLANNED);
    }

    @Test
    void updateProject_updatesUpdatedAt() {
        Project project = projectIn(tenant);
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        stubSaveReturnsArgument();
        ProjectUpdateRequestDto request = new ProjectUpdateRequestDto();
        request.setName("Renamed");

        ProjectResponseDto updated = projectService.updateProject(project.getId(), request, actor.getId());

        assertThat(updated.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void updateProject_whenOnlyTargetDateSentAndItIsBeforeTheExistingStartDate_throwsInvalidProjectDatesException() {
        Project project = projectIn(tenant);
        project.setStartDate(LocalDate.parse("2026-11-01"));
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        ProjectUpdateRequestDto request = new ProjectUpdateRequestDto();
        request.setTargetDate(JsonNullable.of(LocalDate.parse("2026-10-01")));

        assertThatThrownBy(() -> projectService.updateProject(project.getId(), request, actor.getId()))
                .isInstanceOf(InvalidProjectDatesException.class);
        verify(projectRepository, never()).save(any());
    }

    @Test
    void updateProject_whenProjectIsInAnotherTenant_throwsForbiddenActionException() {
        Project project = projectIn(otherTenant);
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        ProjectUpdateRequestDto request = new ProjectUpdateRequestDto();
        request.setName("Not mine");

        assertThatThrownBy(() -> projectService.updateProject(project.getId(), request, actor.getId()))
                .isInstanceOf(ForbiddenActionException.class);
        verify(projectRepository, never()).save(any());
    }

    @Test
    void updateProject_whenNewOwnerIsInAnotherTenant_throwsForbiddenActionException() {
        Project project = projectIn(tenant);
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        User foreignOwner = userIn(otherTenant, UserRole.EMPLOYEE);
        when(userRepository.findById(foreignOwner.getId())).thenReturn(Optional.of(foreignOwner));
        ProjectUpdateRequestDto request = new ProjectUpdateRequestDto();
        request.setOwnerId(JsonNullable.of(foreignOwner.getId().toString()));

        assertThatThrownBy(() -> projectService.updateProject(project.getId(), request, actor.getId()))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void updateProject_whenNotFound_throwsProjectNotFoundException() {
        UUID missing = UUID.randomUUID();
        when(projectRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.updateProject(missing, new ProjectUpdateRequestDto(), actor.getId()))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    // ---- delete ----

    @Test
    void deleteProject_whenProjectHasNoTasks_deletesIt() {
        Project project = projectIn(tenant);
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        when(projectRepository.countTasksByProjectId(project.getId())).thenReturn(0L);

        projectService.deleteProject(project.getId(), actor.getId());

        verify(projectRepository).delete(project);
    }

    @Test
    void deleteProject_whenProjectHasTasks_throwsProjectHasTasksExceptionAndKeepsIt() {
        Project project = projectIn(tenant);
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        when(projectRepository.countTasksByProjectId(project.getId())).thenReturn(12L);

        assertThatThrownBy(() -> projectService.deleteProject(project.getId(), actor.getId()))
                .isInstanceOf(ProjectHasTasksException.class)
                .hasMessageContaining("12 tasks");
        verify(projectRepository, never()).delete(any());
    }

    @Test
    void deleteProject_whenProjectIsInAnotherTenant_throwsForbiddenActionException() {
        Project project = projectIn(otherTenant);
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> projectService.deleteProject(project.getId(), actor.getId()))
                .isInstanceOf(ForbiddenActionException.class);
        verify(projectRepository, never()).delete(any());
    }

    @Test
    void deleteProject_whenNotFound_throwsProjectNotFoundException() {
        UUID missing = UUID.randomUUID();
        when(projectRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.deleteProject(missing, actor.getId()))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void deleteProject_auditsTheDeletionWithTheProjectsLastValues() {
        Project project = projectIn(tenant);
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        when(projectRepository.countTasksByProjectId(project.getId())).thenReturn(0L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);

        projectService.deleteProject(project.getId(), actor.getId());

        verify(auditService).createUpdateAuditLog(eq("PROJECT_DELETED"), eq("Project"), eq(project.getId()),
                eq(tenant), eq(actor), before.capture(), eq(Map.of()), isNull());
        assertThat(before.getValue()).containsEntry("name", "Checkout Revamp");
    }

    // ---- summary ----

    @Test
    void listProjectSummaries_returnsCountsAndProgressExcludingRejectedAndCancelledTasks() {
        Project project = projectIn(tenant);
        stubFilteredPage(List.of(project));
        stubStats(List.of(stats(project.getId(), 40, 10, 12, 2, 14, 2, 3, PROJECT_UPDATED_AT)));

        PageResponseDto<ProjectSummaryResponseDto> page =
                projectService.listProjectSummaries(new ProjectFilterDto(), PageRequest.of(0, 20), actor.getId());

        ProjectSummaryResponseDto summary = page.getContent().get(0);
        assertThat(summary.getProjectId()).isEqualTo(project.getId());
        assertThat(summary.getTaskCounts().getTotal()).isEqualTo(40);
        assertThat(summary.getTaskCounts().getOpen()).isEqualTo(10);
        assertThat(summary.getTaskCounts().getInProgress()).isEqualTo(12);
        assertThat(summary.getTaskCounts().getSnoozed()).isEqualTo(2);
        assertThat(summary.getTaskCounts().getDone()).isEqualTo(14);
        assertThat(summary.getTaskCounts().getClosedWithoutDone()).isEqualTo(2);
        assertThat(summary.getTaskCounts().getOverdue()).isEqualTo(3);
        // 14 / (40 - 2) * 100 = 36.8 -> 37
        assertThat(summary.getProgressPercent()).isEqualTo(37);
    }

    @Test
    void listProjectSummaries_whenProjectHasNoTasks_returnsZeroCountsAndZeroProgress() {
        Project project = projectIn(tenant);
        stubFilteredPage(List.of(project));
        stubStats(List.of());

        PageResponseDto<ProjectSummaryResponseDto> page =
                projectService.listProjectSummaries(new ProjectFilterDto(), PageRequest.of(0, 20), actor.getId());

        ProjectSummaryResponseDto summary = page.getContent().get(0);
        assertThat(summary.getTaskCounts().getTotal()).isZero();
        assertThat(summary.getTaskCounts().getOverdue()).isZero();
        assertThat(summary.getProgressPercent()).isZero();
        assertThat(summary.getLastActivityAt()).isEqualTo(PROJECT_UPDATED_AT);
    }

    @Test
    void listProjectSummaries_whenEveryTaskIsRejectedOrCancelled_progressIsZero() {
        Project project = projectIn(tenant);
        stubFilteredPage(List.of(project));
        stubStats(List.of(stats(project.getId(), 3, 0, 0, 0, 0, 3, 0, PROJECT_UPDATED_AT)));

        PageResponseDto<ProjectSummaryResponseDto> page =
                projectService.listProjectSummaries(new ProjectFilterDto(), PageRequest.of(0, 20), actor.getId());

        assertThat(page.getContent().get(0).getProgressPercent()).isZero();
    }

    @Test
    void listProjectSummaries_whenAllRelevantTasksAreDone_progressIs100() {
        Project project = projectIn(tenant);
        stubFilteredPage(List.of(project));
        stubStats(List.of(stats(project.getId(), 5, 0, 0, 0, 4, 1, 0, PROJECT_UPDATED_AT)));

        PageResponseDto<ProjectSummaryResponseDto> page =
                projectService.listProjectSummaries(new ProjectFilterDto(), PageRequest.of(0, 20), actor.getId());

        assertThat(page.getContent().get(0).getProgressPercent()).isEqualTo(100);
    }

    @Test
    void listProjectSummaries_lastActivityAtIsTheLatestOfProjectAndTaskUpdates() {
        Project project = projectIn(tenant);
        Instant laterTaskUpdate = Instant.parse("2026-10-13T16:20:00Z");
        stubFilteredPage(List.of(project));
        stubStats(List.of(stats(project.getId(), 1, 1, 0, 0, 0, 0, 0, laterTaskUpdate)));

        PageResponseDto<ProjectSummaryResponseDto> page =
                projectService.listProjectSummaries(new ProjectFilterDto(), PageRequest.of(0, 20), actor.getId());

        assertThat(page.getContent().get(0).getLastActivityAt()).isEqualTo(laterTaskUpdate);
    }

    @Test
    void listProjectSummaries_whenProjectUpdatedAfterItsTasks_lastActivityAtIsTheProjectUpdate() {
        Project project = projectIn(tenant);
        stubFilteredPage(List.of(project));
        stubStats(List.of(stats(project.getId(), 1, 1, 0, 0, 0, 0, 0, Instant.parse("2026-09-01T00:00:00Z"))));

        PageResponseDto<ProjectSummaryResponseDto> page =
                projectService.listProjectSummaries(new ProjectFilterDto(), PageRequest.of(0, 20), actor.getId());

        assertThat(page.getContent().get(0).getLastActivityAt()).isEqualTo(PROJECT_UPDATED_AT);
    }

    @Test
    void listProjectSummaries_whenPageIsEmpty_doesNotQueryTaskCounts() {
        stubFilteredPage(List.of());

        PageResponseDto<ProjectSummaryResponseDto> page =
                projectService.listProjectSummaries(new ProjectFilterDto(), PageRequest.of(0, 20), actor.getId());

        assertThat(page.getContent()).isEmpty();
        verify(projectRepository, never()).findTaskStatsByProjectIds(anyCollection(), anyCollection(), any(), any(),
                any(), anyCollection(), anyCollection(), any());
    }

    @Test
    void listProjectSummaries_isLimitedToTheActorsTenantLikeTheList() {
        User teamLead = userIn(tenant, UserRole.TEAM_LEAD);
        when(userRepository.findById(teamLead.getId())).thenReturn(Optional.of(teamLead));
        stubFilteredPage(List.of());

        projectService.listProjectSummaries(new ProjectFilterDto(), PageRequest.of(0, 20), teamLead.getId());

        verify(projectRepository).findFiltered(eq(false), eq(tenant.getId()), anyBoolean(), any(), anyBoolean(),
                any(), anyBoolean(), any(), anyBoolean(), any(), any(Pageable.class));
    }
}
