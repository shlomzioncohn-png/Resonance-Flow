package com.rnd.inbox.task.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.inbox.support.PostgresIntegrationTest;
import com.rnd.inbox.task.dto.TaskCreateRequestDto;
import com.rnd.inbox.task.dto.TaskStatusUpdateRequestDto;
import com.rnd.inbox.task.dto.TaskUpdateRequestDto;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Dedicated cross-tenant isolation suite for the full HTTP stack. TaskAuthorizationTest
// (task/security/) already proves the TaskAuthorization component's own logic in isolation
// (mocked, no DB, no HTTP) - it can't prove the controller/service/repository actually wire that
// logic in correctly. This test drives real requests through the real chain (real Postgres, real
// TaskRepository.findFiltered JPQL, real JSON responses) specifically to prove no actor can see
// or change another tenant's task data - e.g. it would catch a bug where bypassTenantScope got
// dropped, or where a new filter param accidentally bypassed the tenant WHERE clause.
@AutoConfigureMockMvc
class TaskTenantIsolationIntegrationTest extends PostgresIntegrationTest {

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

    private Tenant tenantA;
    private Tenant tenantB;
    private User tenantAEmployee;
    private User tenantBAdmin;
    private User systemAdmin;
    private Task taskA;
    private Task taskB;

    @BeforeEach
    void setUp() {
        tenantA = persistTenant();
        tenantB = persistTenant();
        tenantAEmployee = persistUser(tenantA, UserRole.EMPLOYEE);
        tenantBAdmin = persistUser(tenantB, UserRole.TENANT_ADMIN);
        // Own tenant is irrelevant for SYSTEM_ADMIN's scope, but User.tenant is NOT NULL in the
        // schema, so every user (including a system admin) still needs some tenant row.
        systemAdmin = persistUser(tenantA, UserRole.SYSTEM_ADMIN);
        taskA = persistTask(tenantA, "Tenant A confidential task");
        taskB = persistTask(tenantB, "Tenant B confidential task");
    }

    @AfterEach
    void cleanUp() {
        taskRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    private Tenant persistTenant() {
        Tenant t = new Tenant();
        t.setName("Acme");
        t.setSlug("acme-" + UUID.randomUUID());
        t.setStatus(TenantStatus.ACTIVE);
        t.setCreatedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        return tenantRepository.saveAndFlush(t);
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

    @Test
    void getTaskById_crossTenantAdmin_returns403AndLeaksNothing() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/tasks/{id}", taskA.getId())
                        .header(ACTOR_HEADER, tenantBAdmin.getId().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("Tenant A confidential task");
    }

    @Test
    void getTaskById_sameTenantEmployee_returns200() throws Exception {
        // No @RequireRole on TaskController - any role within the owning tenant can read.
        mockMvc.perform(get("/api/v1/tasks/{id}", taskA.getId())
                        .header(ACTOR_HEADER, tenantAEmployee.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskA.getId().toString()));
    }

    @Test
    void getTaskById_systemAdmin_seesAnyTenantsTask() throws Exception {
        mockMvc.perform(get("/api/v1/tasks/{id}", taskB.getId())
                        .header(ACTOR_HEADER, systemAdmin.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskB.getId().toString()));
    }

    @Test
    void listTasks_tenantAdmin_neverSeesOtherTenantsTask() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/tasks?size=100")
                        .header(ACTOR_HEADER, tenantBAdmin.getId().toString()))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(taskA.getId().toString());
        assertThat(body).doesNotContain("Tenant A confidential task");
        assertThat(body).contains(taskB.getId().toString());
    }

    @Test
    void listTasks_withSearchMatchingOtherTenantsTask_stillReturnsNothing() throws Exception {
        // Proves the filter feature doesn't bypass tenant scoping - even when the search term
        // matches tenant A's task exactly, tenant B's admin gets nothing back for it.
        MvcResult result = mockMvc.perform(get("/api/v1/tasks?search=confidential")
                        .header(ACTOR_HEADER, tenantBAdmin.getId().toString()))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(taskA.getId().toString());
        assertThat(body).contains(taskB.getId().toString());
    }

    @Test
    void listTasks_systemAdmin_seesBothTenants() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/tasks?size=100")
                        .header(ACTOR_HEADER, systemAdmin.getId().toString()))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains(taskA.getId().toString());
        assertThat(body).contains(taskB.getId().toString());
    }

    @Test
    void updateTask_crossTenantAdmin_returns403AndTaskUnchanged() throws Exception {
        TaskUpdateRequestDto request = new TaskUpdateRequestDto();
        request.setTitle("Hijacked by tenant B admin");

        mockMvc.perform(patch("/api/v1/tasks/{id}", taskA.getId())
                        .header(ACTOR_HEADER, tenantBAdmin.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        assertThat(taskRepository.findById(taskA.getId()).orElseThrow().getTitle())
                .isEqualTo("Tenant A confidential task");
    }

    @Test
    void updateStatus_crossTenantAdmin_returns403AndStatusUnchanged() throws Exception {
        TaskStatusUpdateRequestDto request = new TaskStatusUpdateRequestDto();
        request.setStatus("DONE");

        mockMvc.perform(patch("/api/v1/tasks/{id}/status", taskA.getId())
                        .header(ACTOR_HEADER, tenantBAdmin.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        assertThat(taskRepository.findById(taskA.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.NEW);
    }

    @Test
    void createTask_inAnotherTenant_returns403AndDoesNotPersist() throws Exception {
        TaskCreateRequestDto request = new TaskCreateRequestDto();
        request.setTenantId(tenantA.getId().toString());
        request.setTitle("Planted by tenant B admin");

        mockMvc.perform(post("/api/v1/tasks")
                        .header(ACTOR_HEADER, tenantBAdmin.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        assertThat(taskRepository.findAll())
                .extracting(Task::getTitle)
                .doesNotContain("Planted by tenant B admin");
    }

    @Test
    void updateTask_sameTenantEmployee_succeeds() throws Exception {
        // Confirms isolation isn't accidentally blocking everyone in-tenant, only cross-tenant
        // actors - a non-admin role in the owning tenant genuinely can update.
        TaskUpdateRequestDto request = new TaskUpdateRequestDto();
        request.setTitle("Updated by employee");

        mockMvc.perform(patch("/api/v1/tasks/{id}", taskA.getId())
                        .header(ACTOR_HEADER, tenantAEmployee.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        assertThat(taskRepository.findById(taskA.getId()).orElseThrow().getTitle())
                .isEqualTo("Updated by employee");
    }
}
