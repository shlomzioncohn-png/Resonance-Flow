package com.rnd.inbox.project;

import com.rnd.inbox.support.PostgresIntegrationTest;
import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.model.TaskExtractionProvenance;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.repository.TaskRepository;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.team.model.TeamStatus;
import com.rnd.inbox.team.repository.TeamRepository;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.tenant.model.TenantStatus;
import com.rnd.inbox.tenant.repository.TenantRepository;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.user.model.UserStatus;
import com.rnd.inbox.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Runs the custom @Query methods of ProjectRepository against the real database: a mocked
// repository can't catch a broken JPQL string or a wrong status bucket.
class ProjectRepositoryTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Set<TaskStatus> OPEN = Set.of(TaskStatus.NEW, TaskStatus.IN_REVIEW, TaskStatus.APPROVED);
    private static final Set<TaskStatus> CLOSED_WITHOUT_DONE = Set.of(TaskStatus.REJECTED, TaskStatus.CANCELLED);
    private static final Set<TaskStatus> FINISHED = Set.of(TaskStatus.DONE, TaskStatus.REJECTED, TaskStatus.CANCELLED);

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamRepository teamRepository;

    private final List<UUID> createdTenantIds = new ArrayList<>();
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenant = persistTenant();
    }

    @AfterEach
    void cleanUp() {
        deleteTenantData(createdTenantIds);
    }

    private Tenant persistTenant() {
        Tenant t = new Tenant();
        t.setName("Acme");
        t.setSlug("acme-" + UUID.randomUUID());
        t.setStatus(TenantStatus.ACTIVE);
        t.setCreatedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        Tenant saved = tenantRepository.saveAndFlush(t);
        createdTenantIds.add(saved.getId());
        return saved;
    }

    private User persistUser(Tenant tenant) {
        User user = new User();
        user.setTenant(tenant);
        user.setDisplayName("User " + UUID.randomUUID());
        user.setRole(UserRole.EMPLOYEE);
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.saveAndFlush(user);
    }

    private Team persistTeam(Tenant tenant) {
        Team team = new Team();
        team.setTenant(tenant);
        team.setName("Team " + UUID.randomUUID());
        team.setTeamLead(persistUser(tenant));
        team.setStatus(TeamStatus.ACTIVE);
        team.setCreatedAt(Instant.now());
        team.setUpdatedAt(Instant.now());
        return teamRepository.saveAndFlush(team);
    }

    private Project persistProject(Tenant tenant, String name, ProjectStatus status, Team team, User owner) {
        Project project = new Project();
        project.setTenant(tenant);
        project.setName(name);
        project.setStatus(status);
        project.setTeam(team);
        project.setOwner(owner);
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(Instant.now());
        return projectRepository.saveAndFlush(project);
    }

    private Project persistProject(Tenant tenant, String name) {
        return persistProject(tenant, name, ProjectStatus.ACTIVE, null, null);
    }

    private Task persistTask(Project project, TaskStatus status, Instant deadline, Instant updatedAt) {
        Task task = new Task();
        task.setTenant(project.getTenant());
        task.setProject(project);
        task.setTitle("Task " + UUID.randomUUID());
        task.setSourceType(SourceType.MANUAL);
        task.setStatus(status);
        if (status == TaskStatus.SNOOZED) {
            // chk_tasks_snoozed_until: a snoozed task must say until when.
            task.setSnoozedUntil(NOW.plusSeconds(86400));
        }
        task.setAvailable(true);
        task.setExtractionProvenance(TaskExtractionProvenance.MANUAL);
        task.setDeadline(deadline);
        task.setCreatedAt(updatedAt);
        task.setUpdatedAt(updatedAt);
        return taskRepository.saveAndFlush(task);
    }

    private Page<Project> findInTenant(UUID tenantId, String searchPattern, ProjectStatus status, UUID teamId,
                                       UUID ownerId) {
        return projectRepository.findFiltered(false, tenantId,
                searchPattern != null, searchPattern,
                status != null, status,
                teamId != null, teamId,
                ownerId != null, ownerId,
                PageRequest.of(0, 20, Sort.by("name", "id")));
    }

    private List<ProjectTaskStats> statsFor(UUID... projectIds) {
        return projectRepository.findTaskStatsByProjectIds(List.of(projectIds), OPEN, TaskStatus.IN_PROGRESS,
                TaskStatus.SNOOZED, TaskStatus.DONE, CLOSED_WITHOUT_DONE, FINISHED, NOW);
    }

    // ---- findFiltered ----

    @Test
    void findFiltered_withTenantScope_returnsOnlyThatTenantsProjects() {
        Project mine = persistProject(tenant, "Mine");
        persistProject(persistTenant(), "Someone else's");

        Page<Project> page = findInTenant(tenant.getId(), null, null, null, null);

        assertThat(page.getContent()).extracting(Project::getId).containsExactly(mine.getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void findFiltered_whenBypassingTenantScope_returnsProjectsOfEveryTenant() {
        Project mine = persistProject(tenant, "Mine");
        Project other = persistProject(persistTenant(), "Other");

        Page<Project> page = projectRepository.findFiltered(true, null, false, null, false, null, false, null,
                false, null, PageRequest.of(0, 1000));

        assertThat(page.getContent()).extracting(Project::getId).contains(mine.getId(), other.getId());
    }

    @Test
    void findFiltered_bySearch_matchesPartOfTheNameIgnoringCase() {
        Project checkout = persistProject(tenant, "Checkout Revamp");
        persistProject(tenant, "Billing");

        Page<Project> page = findInTenant(tenant.getId(), "%checkout%", null, null, null);

        assertThat(page.getContent()).extracting(Project::getId).containsExactly(checkout.getId());
    }

    @Test
    void findFiltered_withSeveralFilters_returnsOnlyProjectsMatchingAllOfThem() {
        Team team = persistTeam(tenant);
        User owner = persistUser(tenant);
        Project match = persistProject(tenant, "Match", ProjectStatus.ACTIVE, team, owner);
        persistProject(tenant, "Wrong status", ProjectStatus.PLANNED, team, owner);
        persistProject(tenant, "Wrong team", ProjectStatus.ACTIVE, persistTeam(tenant), owner);
        persistProject(tenant, "Wrong owner", ProjectStatus.ACTIVE, team, persistUser(tenant));

        Page<Project> page = findInTenant(tenant.getId(), null, ProjectStatus.ACTIVE, team.getId(), owner.getId());

        assertThat(page.getContent()).extracting(Project::getId).containsExactly(match.getId());
    }

    @Test
    void findFiltered_byTeamOfAnotherTenant_returnsAnEmptyPage() {
        Tenant otherTenant = persistTenant();
        Team foreignTeam = persistTeam(otherTenant);
        persistProject(otherTenant, "Foreign", ProjectStatus.ACTIVE, foreignTeam, null);

        Page<Project> page = findInTenant(tenant.getId(), null, null, foreignTeam.getId(), null);

        assertThat(page.getContent()).isEmpty();
    }

    // ---- countTasksByProjectId ----

    @Test
    void countTasksByProjectId_countsTasksInEveryStatus() {
        Project project = persistProject(tenant, "P");
        persistTask(project, TaskStatus.NEW, null, NOW);
        persistTask(project, TaskStatus.DONE, null, NOW);
        persistTask(project, TaskStatus.CANCELLED, null, NOW);
        persistTask(persistProject(tenant, "Other"), TaskStatus.NEW, null, NOW);

        assertThat(projectRepository.countTasksByProjectId(project.getId())).isEqualTo(3);
    }

    @Test
    void countTasksByProjectId_whenProjectHasNoTasks_returnsZero() {
        Project project = persistProject(tenant, "Empty");

        assertThat(projectRepository.countTasksByProjectId(project.getId())).isZero();
    }

    // ---- findTaskStatsByProjectIds ----

    @Test
    void findTaskStatsByProjectIds_putsEveryTaskInExactlyOneStatusBucket() {
        Project project = persistProject(tenant, "P");
        for (TaskStatus status : TaskStatus.values()) {
            persistTask(project, status, null, NOW);
        }

        ProjectTaskStats stats = statsFor(project.getId()).get(0);

        assertThat(stats.getProjectId()).isEqualTo(project.getId());
        assertThat(stats.getTotal()).isEqualTo(8);
        assertThat(stats.getOpen()).isEqualTo(3);
        assertThat(stats.getInProgress()).isEqualTo(1);
        assertThat(stats.getSnoozed()).isEqualTo(1);
        assertThat(stats.getDone()).isEqualTo(1);
        assertThat(stats.getClosedWithoutDone()).isEqualTo(2);
        assertThat(stats.getOpen() + stats.getInProgress() + stats.getSnoozed() + stats.getDone()
                + stats.getClosedWithoutDone()).isEqualTo(stats.getTotal());
    }

    @Test
    void findTaskStatsByProjectIds_countsAsOverdueOnlyUnfinishedTasksPastTheirDeadline() {
        Project project = persistProject(tenant, "P");
        Instant past = NOW.minusSeconds(3600);
        Instant future = NOW.plusSeconds(3600);
        persistTask(project, TaskStatus.IN_PROGRESS, past, NOW);   // overdue
        persistTask(project, TaskStatus.NEW, past, NOW);           // overdue
        persistTask(project, TaskStatus.IN_PROGRESS, future, NOW); // not yet due
        persistTask(project, TaskStatus.NEW, null, NOW);           // no deadline
        persistTask(project, TaskStatus.DONE, past, NOW);          // finished
        persistTask(project, TaskStatus.REJECTED, past, NOW);      // finished
        persistTask(project, TaskStatus.CANCELLED, past, NOW);     // finished

        ProjectTaskStats stats = statsFor(project.getId()).get(0);

        assertThat(stats.getOverdue()).isEqualTo(2);
    }

    @Test
    void findTaskStatsByProjectIds_returnsTheLatestTaskUpdate() {
        Project project = persistProject(tenant, "P");
        Instant latest = Instant.parse("2026-10-13T16:20:00Z");
        persistTask(project, TaskStatus.NEW, null, Instant.parse("2026-10-01T00:00:00Z"));
        persistTask(project, TaskStatus.NEW, null, latest);

        ProjectTaskStats stats = statsFor(project.getId()).get(0);

        assertThat(stats.getLastTaskUpdatedAt()).isEqualTo(latest);
    }

    @Test
    void findTaskStatsByProjectIds_returnsOneRowPerProjectWithTasksAndNoneForProjectsWithout() {
        Project first = persistProject(tenant, "First");
        Project second = persistProject(tenant, "Second");
        Project empty = persistProject(tenant, "Empty");
        persistTask(first, TaskStatus.NEW, null, NOW);
        persistTask(first, TaskStatus.DONE, null, NOW);
        persistTask(second, TaskStatus.DONE, null, NOW);

        List<ProjectTaskStats> stats = statsFor(first.getId(), second.getId(), empty.getId());

        assertThat(stats).extracting(ProjectTaskStats::getProjectId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(stats).filteredOn(s -> s.getProjectId().equals(first.getId()))
                .singleElement().extracting(ProjectTaskStats::getTotal).isEqualTo(2L);
    }
}
