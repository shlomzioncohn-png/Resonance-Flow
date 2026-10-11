package com.rnd.inbox.task;

import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.model.TaskExtractionProvenance;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.repository.TaskRepository;
import com.rnd.inbox.task.service.TaskService;
import com.rnd.inbox.audit.AuditLog;
import com.rnd.inbox.audit.AuditLogRepository;
import com.rnd.inbox.support.PostgresIntegrationTest;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.tenant.repository.TenantRepository;
import com.rnd.inbox.tenant.model.TenantStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class TaskAuditTransactionIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @MockBean
    private AuditLogRepository auditLogRepository;

    private final List<UUID> createdTenantIds = new ArrayList<>();

    // Only the tenants this test created are removed - see PostgresIntegrationTest.
    @AfterEach
    void cleanUp() {
        deleteTenantData(createdTenantIds);
    }

    @Test
    void auditWriteFailureRollsBackTheTaskInsert() {
        Tenant tenant = persistTenant();

        when(auditLogRepository.save(any(AuditLog.class)))
                .thenThrow(new DataIntegrityViolationException("simulated audit write failure"));

        Task incoming = new Task();
        incoming.setTenant(tenant);
        incoming.setTitle("Fix flaky test");
        incoming.setSourceType(SourceType.GITHUB_ISSUE);
        incoming.setSourceRef("github:owner/repo:issue:42");
        incoming.setStatus(TaskStatus.NEW);
        incoming.setExtractionProvenance(TaskExtractionProvenance.ETL);
        incoming.setCreatedAt(Instant.now());
        incoming.setUpdatedAt(Instant.now());

        assertThatThrownBy(() -> taskService.createTask(incoming, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(taskRepository.findByTenant_IdAndSourceTypeAndSourceRef(
                tenant.getId(), SourceType.GITHUB_ISSUE, "github:owner/repo:issue:42"))
                .isEmpty();
    }

    private Tenant persistTenant() {
        Tenant tenant = new Tenant();
        tenant.setName("Acme");
        tenant.setSlug("acme-" + UUID.randomUUID());
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        Tenant saved = tenantRepository.saveAndFlush(tenant);
        createdTenantIds.add(saved.getId());
        return saved;
    }
}
