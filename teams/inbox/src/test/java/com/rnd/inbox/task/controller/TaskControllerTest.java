package com.rnd.inbox.task.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.inbox.support.PostgresIntegrationTest;
import com.rnd.inbox.task.assignment.Assignment;
import com.rnd.inbox.task.assignment.AssignmentRepository;
import com.rnd.inbox.task.assignment.AssignmentRole;
import com.rnd.inbox.task.dto.TaskCreateRequestDto;
import com.rnd.inbox.task.dto.TaskStatusUpdateRequestDto;
import com.rnd.inbox.task.dto.TaskUpdateRequestDto;
import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.model.TaskExtractionProvenance;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.repository.TaskRepository;
import com.rnd.inbox.team.member.TeamMember;
import com.rnd.inbox.team.member.TeamMemberId;
import com.rnd.inbox.team.member.TeamMemberRepository;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Real integration test, not @WebMvcTest: full Spring context, real Postgres, requests driven
// through MockMvc against the actual TaskController -> TaskService -> TaskAuthorization ->
// TaskRepository -> DB chain, nothing mocked. A mocked-repository unit test can never catch a
// broken @Query string (e.g. the t.available/t.isAvailable field-name bug this style of test
// caught the first time it ran for real) - that's the point of testing this way.
@AutoConfigureMockMvc
class TaskControllerTest extends PostgresIntegrationTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    private final List<UUID> createdTenantIds = new ArrayList<>();
    private Tenant tenant;
    private User actor;

    @BeforeEach
    void setUp() {
        tenant = persistTenant();
        actor = persistUser(tenant, UserRole.EMPLOYEE);
    }

    // Every row this test class creates lives under a tenant from persistTenant(), and only
    // those tenants' rows are deleted again here - never a table-wide deleteAll(), because this
    // runs against the shared local database (see PostgresIntegrationTest).
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

    private User persistUser(Tenant tenant, UserRole role) {
        User user = new User();
        user.setTenant(tenant);
        user.setDisplayName("Test User " + UUID.randomUUID());
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.saveAndFlush(user);
    }

    private Task persistTask(Tenant tenant, String title) {
        Task task = new Task();
        task.setTenant(tenant);
        task.setTitle(title);
        task.setSourceType(SourceType.MANUAL);
        task.setStatus(TaskStatus.NEW);
        task.setAvailable(true);
        task.setExtractionProvenance(TaskExtractionProvenance.MANUAL);
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        return taskRepository.saveAndFlush(task);
    }

    private Task persistOwnedTask(Tenant tenant, String title, User owner) {
        Task task = persistTask(tenant, title);
        task.setOwner(owner);
        return taskRepository.saveAndFlush(task);
    }

    private Team persistTeam(Tenant tenant, User teamLead) {
        Team team = new Team();
        team.setTenant(tenant);
        team.setName("Team " + UUID.randomUUID());
        team.setTeamLead(teamLead);
        team.setStatus(TeamStatus.ACTIVE);
        team.setCreatedAt(Instant.now());
        team.setUpdatedAt(Instant.now());
        return teamRepository.saveAndFlush(team);
    }

    // leftAt == null means the membership is still active.
    private void persistTeamMember(Team team, User user, Instant leftAt) {
        TeamMember member = new TeamMember();
        member.setId(new TeamMemberId(team.getId(), user.getId()));
        member.setTeam(team);
        member.setUser(user);
        member.setJoinedAt(Instant.now());
        member.setLeftAt(leftAt);
        teamMemberRepository.saveAndFlush(member);
    }

    // Exactly one of user/team is set - the assignments table's CHECK constraint rejects both
    // or neither.
    private void persistAssignment(Task task, User user, Team team) {
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setUser(user);
        assignment.setTeam(team);
        assignment.setAssignmentRole(AssignmentRole.ASSIGNEE);
        assignment.setAssignedAt(Instant.now());
        assignmentRepository.saveAndFlush(assignment);
    }

    private TaskCreateRequestDto validCreateRequest() {
        TaskCreateRequestDto request = new TaskCreateRequestDto();
        request.setTenantId(tenant.getId().toString());
        request.setTitle("Fix flaky test");
        return request;
    }

    @Test
    void createTask_withValidRequest_persistsAndReturns201() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .header(ACTOR_HEADER, actor.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/tasks/")))
                .andExpect(jsonPath("$.title").value("Fix flaky test"));

        assertThat(countTasksInTenant(tenant.getId())).isEqualTo(1);
    }

    @Test
    void createTask_withMissingRequiredFields_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .header(ACTOR_HEADER, actor.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TaskCreateRequestDto())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.field_errors").isArray());

        assertThat(countTasksInTenant(tenant.getId())).isZero();
    }

    @Test
    void createTask_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void createTask_whenTenantDoesNotExist_returns404() throws Exception {
        TaskCreateRequestDto request = validCreateRequest();
        request.setTenantId(UUID.randomUUID().toString());

        mockMvc.perform(post("/api/v1/tasks")
                        .header(ACTOR_HEADER, actor.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TENANT_NOT_FOUND"));
    }

    @Test
    void createTask_whenActorOutsideRequestedTenant_returns403AndDoesNotPersist() throws Exception {
        Tenant otherTenant = persistTenant();
        TaskCreateRequestDto request = validCreateRequest();
        request.setTenantId(otherTenant.getId().toString());

        mockMvc.perform(post("/api/v1/tasks")
                        .header(ACTOR_HEADER, actor.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        assertThat(countTasksInTenant(otherTenant.getId())).isZero();
        assertThat(countTasksInTenant(tenant.getId())).isZero();
    }

    @Test
    void getTaskById_whenFound_returns200WithRealPersistedData() throws Exception {
        Task task = persistTask(tenant, "Fix flaky test");

        mockMvc.perform(get("/api/v1/tasks/{id}", task.getId()).header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(task.getId().toString()))
                .andExpect(jsonPath("$.title").value("Fix flaky test"));
    }

    @Test
    void getTaskById_withoutActorHeader_returns401() throws Exception {
        Task task = persistTask(tenant, "Fix flaky test");

        mockMvc.perform(get("/api/v1/tasks/{id}", task.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void getTaskById_whenNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/tasks/{id}", UUID.randomUUID()).header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TASK_NOT_FOUND"));
    }

    @Test
    void getTaskById_whenActorOutsideTargetTenant_returns403() throws Exception {
        Tenant otherTenant = persistTenant();
        Task task = persistTask(otherTenant, "Someone else's task");

        mockMvc.perform(get("/api/v1/tasks/{id}", task.getId()).header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void listTasks_returns200WithOwnTenantTasksOnly() throws Exception {
        Task own = persistTask(tenant, "My task");
        persistTask(persistTenant(), "Not mine");

        mockMvc.perform(get("/api/v1/tasks").header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(own.getId().toString()))
                .andExpect(jsonPath("$.total_elements").value(1));
    }

    @Test
    void listTasks_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void listTasks_withStatusFilter_returnsOnlyMatchingStatus() throws Exception {
        persistTask(tenant, "New one");
        Task doneTask = persistTask(tenant, "Done one");
        doneTask.setStatus(TaskStatus.DONE);
        taskRepository.saveAndFlush(doneTask);

        mockMvc.perform(get("/api/v1/tasks?status=DONE").header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(doneTask.getId().toString()));
    }

    @Test
    void listTasks_withAvailableFalseFilter_returnsOnlyUnavailableTasks() throws Exception {
        persistTask(tenant, "Available one");
        Task blocked = persistTask(tenant, "Blocked one");
        blocked.setAvailable(false);
        taskRepository.saveAndFlush(blocked);

        mockMvc.perform(get("/api/v1/tasks?available=false").header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(blocked.getId().toString()));
    }

    @Test
    void listTasks_withSearchMatchingTitleOrDescription_returnsMatch() throws Exception {
        Task matchByTitle = persistTask(tenant, "Quarterly report");
        Task noMatch = persistTask(tenant, "Unrelated task");

        mockMvc.perform(get("/api/v1/tasks?search=quarterly").header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(matchByTitle.getId().toString()));

        assertThat(noMatch.getId()).isNotEqualTo(matchByTitle.getId());
    }

    @Test
    void listTasks_withUserIdFilter_returnsOwnedAndAssignedTasksOnly() throws Exception {
        User target = persistUser(tenant, UserRole.EMPLOYEE);
        User other = persistUser(tenant, UserRole.EMPLOYEE);
        Team activeTeam = persistTeam(tenant, other);
        persistTeamMember(activeTeam, target, null);
        Team formerTeam = persistTeam(tenant, other);
        persistTeamMember(formerTeam, target, Instant.now());

        Task owned = persistOwnedTask(tenant, "Owned by target", target);
        Task assignedDirectly = persistTask(tenant, "Assigned to target");
        persistAssignment(assignedDirectly, target, null);
        Task assignedToActiveTeam = persistTask(tenant, "Assigned to target's team");
        persistAssignment(assignedToActiveTeam, null, activeTeam);
        // Owned AND assigned - must still come back exactly once.
        Task ownedAndAssigned = persistOwnedTask(tenant, "Owned and assigned", target);
        persistAssignment(ownedAndAssigned, target, null);

        persistTask(tenant, "Unrelated, no owner");
        persistOwnedTask(tenant, "Owned by someone else", other);
        persistAssignment(persistTask(tenant, "Assigned to someone else"), other, null);
        persistAssignment(persistTask(tenant, "Assigned to a team target left"), null, formerTeam);
        // Target's team is on these two, but someone else is personally responsible for each -
        // as owner in the first, as individual assignee in the second - so they are not target's.
        persistAssignment(persistOwnedTask(tenant, "Team task owned by someone else", other), null, activeTeam);
        Task teamTaskAssignedToOther = persistTask(tenant, "Team task assigned to someone else");
        persistAssignment(teamTaskAssignedToOther, null, activeTeam);
        persistAssignment(teamTaskAssignedToOther, other, null);

        mockMvc.perform(get("/api/v1/tasks?userId={userId}", target.getId())
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_elements").value(4))
                .andExpect(jsonPath("$.content[*].id", containsInAnyOrder(
                        owned.getId().toString(),
                        assignedDirectly.getId().toString(),
                        assignedToActiveTeam.getId().toString(),
                        ownedAndAssigned.getId().toString())));
    }

    @Test
    void listTasks_withUserIdFilterCombinedWithStatus_appliesBoth() throws Exception {
        User target = persistUser(tenant, UserRole.EMPLOYEE);
        persistOwnedTask(tenant, "Still new", target);
        Task done = persistOwnedTask(tenant, "Already done", target);
        done.setStatus(TaskStatus.DONE);
        taskRepository.saveAndFlush(done);

        mockMvc.perform(get("/api/v1/tasks?status=DONE&userId={userId}", target.getId())
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(done.getId().toString()));
    }

    @Test
    void listTasks_withUserIdOfUnknownUser_returnsEmptyPage() throws Exception {
        persistTask(tenant, "Some task");

        mockMvc.perform(get("/api/v1/tasks?userId={userId}", UUID.randomUUID())
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.total_elements").value(0));
    }

    @Test
    void listTasks_withMalformedUserId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/tasks?userId=not-a-uuid").header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void listTasks_withInvalidStatusEnum_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/tasks?status=NOT_A_REAL_STATUS").header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void listTasks_withDeadlineFromButNoDeadlineTo_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/tasks?deadlineFrom=2026-01-01T00:00:00Z")
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TASK_FILTER"));
    }

    @Test
    void listTasks_withNoDeadlineCombinedWithDeadlineRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/tasks?noDeadline=true&deadlineFrom=2026-01-01T00:00:00Z&deadlineTo=2026-02-01T00:00:00Z")
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TASK_FILTER"));
    }

    @Test
    void listTasks_sortedByDeadline_returnsTasksInThatOrder() throws Exception {
        Task sooner = persistTask(tenant, "Due sooner");
        sooner.setDeadline(Instant.parse("2026-11-01T00:00:00Z"));
        taskRepository.saveAndFlush(sooner);
        Task later = persistTask(tenant, "Due later");
        later.setDeadline(Instant.parse("2026-12-01T00:00:00Z"));
        taskRepository.saveAndFlush(later);

        mockMvc.perform(get("/api/v1/tasks?sort=deadline,desc")
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Due later"))
                .andExpect(jsonPath("$.content[1].title").value("Due sooner"));
    }

    @Test
    void listTasks_sortedBySnakeCaseName_isAccepted() throws Exception {
        persistTask(tenant, "Any task");

        mockMvc.perform(get("/api/v1/tasks?sort=priority_score,desc&sort=created_at,asc")
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void listTasks_sortedByUnsupportedProperty_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/tasks?sort=title,asc")
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TASK_SORT"));
    }

    @Test
    void listTasks_sortedByUnknownProperty_returns400NotServerError() throws Exception {
        mockMvc.perform(get("/api/v1/tasks?sort=noSuchColumn,asc")
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TASK_SORT"));
    }

    @Test
    void listTasks_withoutSort_pagesThroughEveryTaskExactlyOnceNewestFirst() throws Exception {
        Instant base = Instant.parse("2026-10-01T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            Task task = persistTask(tenant, "Task " + i);
            // Tasks 3 and 4 share a creation instant, so only the id tie-breaker orders them.
            task.setCreatedAt(base.plusSeconds(Math.min(i, 3)));
            taskRepository.saveAndFlush(task);
        }

        List<String> seen = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String body = mockMvc.perform(get("/api/v1/tasks?size=2&page=" + page)
                            .header(ACTOR_HEADER, actor.getId().toString()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            objectMapper.readTree(body).get("content").forEach(task -> {
                seen.add(task.get("id").asText());
                titles.add(task.get("title").asText());
            });
        }

        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
        assertThat(titles.subList(0, 2)).containsExactlyInAnyOrder("Task 3", "Task 4");
        assertThat(titles.subList(2, 5)).containsExactly("Task 2", "Task 1", "Task 0");
    }

    @Test
    void updateTask_withValidRequest_returns200AndPersistsChange() throws Exception {
        Task task = persistTask(tenant, "Old title");
        TaskUpdateRequestDto request = new TaskUpdateRequestDto();
        request.setTitle("New title");

        mockMvc.perform(patch("/api/v1/tasks/{id}", task.getId())
                        .header(ACTOR_HEADER, actor.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New title"));

        assertThat(taskRepository.findById(task.getId()).orElseThrow().getTitle()).isEqualTo("New title");
    }

    @Test
    void updateTask_withoutActorHeader_returns401() throws Exception {
        Task task = persistTask(tenant, "Old title");
        TaskUpdateRequestDto request = new TaskUpdateRequestDto();
        request.setTitle("New title");

        mockMvc.perform(patch("/api/v1/tasks/{id}", task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void updateTask_whenNotFound_returns404() throws Exception {
        TaskUpdateRequestDto request = new TaskUpdateRequestDto();
        request.setTitle("New title");

        mockMvc.perform(patch("/api/v1/tasks/{id}", UUID.randomUUID())
                        .header(ACTOR_HEADER, actor.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TASK_NOT_FOUND"));
    }

    @Test
    void updateTask_whenActorOutsideTargetTenant_returns403AndLeavesTaskUnchanged() throws Exception {
        Tenant otherTenant = persistTenant();
        Task task = persistTask(otherTenant, "Someone else's task");
        TaskUpdateRequestDto request = new TaskUpdateRequestDto();
        request.setTitle("Hijacked title");

        mockMvc.perform(patch("/api/v1/tasks/{id}", task.getId())
                        .header(ACTOR_HEADER, actor.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        assertThat(taskRepository.findById(task.getId()).orElseThrow().getTitle())
                .isEqualTo("Someone else's task");
    }

    @Test
    void updateStatus_withValidStatus_returns200AndPersistsChange() throws Exception {
        Task task = persistTask(tenant, "Task");
        TaskStatusUpdateRequestDto request = new TaskStatusUpdateRequestDto();
        request.setStatus("DONE");

        mockMvc.perform(patch("/api/v1/tasks/{id}/status", task.getId())
                        .header(ACTOR_HEADER, actor.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void updateStatus_withInvalidStatusValue_returns400() throws Exception {
        Task task = persistTask(tenant, "Task");
        TaskStatusUpdateRequestDto request = new TaskStatusUpdateRequestDto();
        request.setStatus("NOT_A_REAL_STATUS");

        mockMvc.perform(patch("/api/v1/tasks/{id}/status", task.getId())
                        .header(ACTOR_HEADER, actor.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void updateStatus_whenSnoozedWithoutSnoozedUntil_returns400() throws Exception {
        Task task = persistTask(tenant, "Task");
        TaskStatusUpdateRequestDto request = new TaskStatusUpdateRequestDto();
        request.setStatus("SNOOZED");

        mockMvc.perform(patch("/api/v1/tasks/{id}/status", task.getId())
                        .header(ACTOR_HEADER, actor.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TASK_STATUS_TRANSITION"));
    }

    @Test
    void updateStatus_whenActorOutsideTargetTenant_returns403AndLeavesStatusUnchanged() throws Exception {
        Tenant otherTenant = persistTenant();
        Task task = persistTask(otherTenant, "Someone else's task");
        TaskStatusUpdateRequestDto request = new TaskStatusUpdateRequestDto();
        request.setStatus("DONE");

        mockMvc.perform(patch("/api/v1/tasks/{id}/status", task.getId())
                        .header(ACTOR_HEADER, actor.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.NEW);
    }

    @Test
    void updateStatus_whenNotFound_returns404() throws Exception {
        TaskStatusUpdateRequestDto request = new TaskStatusUpdateRequestDto();
        request.setStatus("DONE");

        mockMvc.perform(patch("/api/v1/tasks/{id}/status", UUID.randomUUID())
                        .header(ACTOR_HEADER, actor.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TASK_NOT_FOUND"));
    }
}
