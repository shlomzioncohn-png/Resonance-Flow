package com.rnd.inbox.taskselection;

import com.rnd.inbox.support.PostgresIntegrationTest;
import com.rnd.inbox.task.assignment.Assignment;
import com.rnd.inbox.task.assignment.AssignmentRepository;
import com.rnd.inbox.task.assignment.AssignmentRole;
import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.model.TaskExtractionProvenance;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.repository.TaskRepository;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Real integration test, same style as TaskControllerTest: full Spring context, real Postgres,
// requests go through TaskSelectionController -> TaskSelectionService -> TaskRepository -> DB
// with nothing mocked.
@AutoConfigureMockMvc
class TaskSelectionControllerTest extends PostgresIntegrationTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";
    private static final String SELECTION_URL = "/api/v1/tasks/selection";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    private final List<UUID> createdTenantIds = new ArrayList<>();
    private Tenant tenant;
    private User actor;

    @BeforeEach
    void setUp() {
        tenant = persistTenant();
        actor = persistUser(tenant, UserRole.EMPLOYEE);
    }

    // Only the tenants this test created are removed - see PostgresIntegrationTest.
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

    // An available, approved task taking `effortMinutes`, assigned directly to `assignee` -
    // i.e. a task that is eligible for selection for that user.
    private Task persistTaskAssignedTo(Tenant tenant, User assignee, String title, int effortMinutes) {
        Task task = new Task();
        task.setTenant(tenant);
        task.setTitle(title);
        task.setSourceType(SourceType.MANUAL);
        task.setStatus(TaskStatus.APPROVED);
        task.setAvailable(true);
        task.setPriorityScore(5f);
        task.setEffortEstimateMinutes(effortMinutes);
        task.setEffortEstimateConfidence(0.9f);
        task.setExtractionProvenance(TaskExtractionProvenance.MANUAL);
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        Task saved = taskRepository.saveAndFlush(task);

        Assignment assignment = new Assignment();
        assignment.setTask(saved);
        assignment.setUser(assignee);
        assignment.setAssignmentRole(AssignmentRole.ASSIGNEE);
        assignment.setAssignedAt(Instant.now());
        assignmentRepository.saveAndFlush(assignment);
        return saved;
    }

    @Test
    void selectTasks_withoutUserId_returns200WithActorsOwnTasks() throws Exception {
        persistTaskAssignedTo(tenant, actor, "Mine", 30);
        persistTaskAssignedTo(tenant, persistUser(tenant, UserRole.EMPLOYEE), "Someone else's", 30);

        mockMvc.perform(get(SELECTION_URL + "?availableMinutes=45")
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected_tasks", hasSize(1)))
                .andExpect(jsonPath("$.selected_tasks[0].title").value("Mine"))
                .andExpect(jsonPath("$.selected_tasks[0].effort_estimate_minutes").value(30))
                .andExpect(jsonPath("$.total_effort_minutes").value(30))
                .andExpect(jsonPath("$.remaining_minutes").value(15))
                .andExpect(jsonPath("$.used_fallback").value(false));
    }

    @Test
    void selectTasks_whenATaskHasNoPriorityScoreYet_returns200AndStillSelectsIt() throws Exception {
        // priority_score stays NULL until Chain scores the task; that used to fail the request.
        Task unscored = persistTaskAssignedTo(tenant, actor, "Not scored yet", 30);
        unscored.setPriorityScore(null);
        taskRepository.saveAndFlush(unscored);

        mockMvc.perform(get(SELECTION_URL + "?availableMinutes=45")
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected_tasks", hasSize(1)))
                .andExpect(jsonPath("$.selected_tasks[0].title").value("Not scored yet"));
    }

    @Test
    void selectTasks_withTaskLongerThanWindow_returnsItWithFallbackFlag() throws Exception {
        persistTaskAssignedTo(tenant, actor, "Long task", 60);

        mockMvc.perform(get(SELECTION_URL + "?availableMinutes=30")
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected_tasks[0].title").value("Long task"))
                .andExpect(jsonPath("$.used_fallback").value(true));
    }

    @Test
    void selectTasks_whenActorHasNoTasks_returns200WithEmptySelection() throws Exception {
        mockMvc.perform(get(SELECTION_URL + "?availableMinutes=45")
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected_tasks", hasSize(0)))
                .andExpect(jsonPath("$.remaining_minutes").value(45));
    }

    @Test
    void selectTasks_withUserIdOfUserInSameTenant_returnsThatUsersTasks() throws Exception {
        User colleague = persistUser(tenant, UserRole.EMPLOYEE);
        persistTaskAssignedTo(tenant, colleague, "Colleague's", 30);
        persistTaskAssignedTo(tenant, actor, "Mine", 30);

        mockMvc.perform(get(SELECTION_URL + "?availableMinutes=45&userId={userId}", colleague.getId())
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected_tasks", hasSize(1)))
                .andExpect(jsonPath("$.selected_tasks[0].title").value("Colleague's"));
    }

    @Test
    void selectTasks_whenNonAdminPassesUserIdFromAnotherTenant_returnsEmptySelection() throws Exception {
        Tenant otherTenant = persistTenant();
        User outsider = persistUser(otherTenant, UserRole.EMPLOYEE);
        persistTaskAssignedTo(otherTenant, outsider, "Other tenant's", 30);

        mockMvc.perform(get(SELECTION_URL + "?availableMinutes=45&userId={userId}", outsider.getId())
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected_tasks", hasSize(0)));
    }

    @Test
    void selectTasks_whenSystemAdminPassesUserIdFromAnotherTenant_returnsThatUsersTasks() throws Exception {
        User admin = persistUser(tenant, UserRole.SYSTEM_ADMIN);
        Tenant otherTenant = persistTenant();
        User outsider = persistUser(otherTenant, UserRole.EMPLOYEE);
        persistTaskAssignedTo(otherTenant, outsider, "Other tenant's", 30);

        mockMvc.perform(get(SELECTION_URL + "?availableMinutes=45&userId={userId}", outsider.getId())
                        .header(ACTOR_HEADER, admin.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected_tasks", hasSize(1)))
                .andExpect(jsonPath("$.selected_tasks[0].title").value("Other tenant's"));
    }

    @Test
    void selectTasks_withUserIdOfUnknownUser_returnsEmptySelection() throws Exception {
        persistTaskAssignedTo(tenant, actor, "Mine", 30);

        mockMvc.perform(get(SELECTION_URL + "?availableMinutes=45&userId={userId}", UUID.randomUUID())
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected_tasks", hasSize(0)));
    }

    @Test
    void selectTasks_withMaxAvailableMinutes_returns200() throws Exception {
        mockMvc.perform(get(SELECTION_URL + "?availableMinutes=480")
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isOk());
    }

    @Test
    void selectTasks_withAvailableMinutesAboveMax_returns400() throws Exception {
        mockMvc.perform(get(SELECTION_URL + "?availableMinutes=481")
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void selectTasks_withHugeAvailableMinutes_returns400() throws Exception {
        mockMvc.perform(get(SELECTION_URL + "?availableMinutes=2000000000")
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void selectTasks_withZeroAvailableMinutes_returns400() throws Exception {
        mockMvc.perform(get(SELECTION_URL + "?availableMinutes=0")
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void selectTasks_withoutAvailableMinutes_returns400() throws Exception {
        mockMvc.perform(get(SELECTION_URL)
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void selectTasks_withNonNumericAvailableMinutes_returns400() throws Exception {
        mockMvc.perform(get(SELECTION_URL + "?availableMinutes=abc")
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void selectTasks_withMalformedUserId_returns400() throws Exception {
        mockMvc.perform(get(SELECTION_URL + "?availableMinutes=45&userId=not-a-uuid")
                        .header(ACTOR_HEADER, actor.getId().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void selectTasks_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(get(SELECTION_URL + "?availableMinutes=45"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void selectTasks_whenActorIdUnknown_returns401() throws Exception {
        mockMvc.perform(get(SELECTION_URL + "?availableMinutes=45")
                        .header(ACTOR_HEADER, UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }
}
