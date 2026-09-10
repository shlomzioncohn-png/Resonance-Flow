package com.rnd.inbox.task;

import com.rnd.inbox.audit.AuditLog;
import com.rnd.inbox.audit.AuditLogRepository;
import com.rnd.inbox.support.PostgresIntegrationTest;
import com.rnd.inbox.tenant.Tenant;
import com.rnd.inbox.tenant.TenantRepository;
import com.rnd.inbox.tenant.TenantStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

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
        return tenantRepository.saveAndFlush(tenant);
    }
}
