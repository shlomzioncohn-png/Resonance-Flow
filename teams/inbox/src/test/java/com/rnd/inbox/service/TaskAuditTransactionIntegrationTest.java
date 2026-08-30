package com.rnd.inbox.service;

import com.rnd.inbox.model.entity.AuditLog;
import com.rnd.inbox.model.entity.Organization;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.model.enums.OrganizationStatus;
import com.rnd.inbox.model.enums.SourceType;
import com.rnd.inbox.model.enums.TaskExtractionProvenance;
import com.rnd.inbox.model.enums.TaskStatus;
import com.rnd.inbox.repository.AuditLogRepository;
import com.rnd.inbox.repository.OrganizationRepository;
import com.rnd.inbox.repository.TaskRepository;
import com.rnd.inbox.support.PostgresIntegrationTest;
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
    private OrganizationRepository organizationRepository;

    @MockBean
    private AuditLogRepository auditLogRepository;

    @Test
    void auditWriteFailureRollsBackTheTaskInsert() {
        Organization organization = persistOrganization();

        when(auditLogRepository.save(any(AuditLog.class)))
                .thenThrow(new DataIntegrityViolationException("simulated audit write failure"));

        Task incoming = new Task();
        incoming.setOrganization(organization);
        incoming.setTitle("Fix flaky test");
        incoming.setSourceType(SourceType.GITHUB_ISSUE);
        incoming.setSourceRef("github:owner/repo:issue:42");
        incoming.setStatus(TaskStatus.NEW);
        incoming.setExtractionProvenance(TaskExtractionProvenance.ETL);
        incoming.setCreatedAt(Instant.now());
        incoming.setUpdatedAt(Instant.now());

        assertThatThrownBy(() -> taskService.createFromBridgeEvent(incoming, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(taskRepository.findByOrganization_IdAndSourceTypeAndSourceRef(
                organization.getId(), SourceType.GITHUB_ISSUE, "github:owner/repo:issue:42"))
                .isEmpty();
    }

    private Organization persistOrganization() {
        Organization organization = new Organization();
        organization.setName("Acme");
        organization.setSlug("acme-" + UUID.randomUUID());
        organization.setStatus(OrganizationStatus.ACTIVE);
        organization.setCreatedAt(Instant.now());
        organization.setUpdatedAt(Instant.now());
        return organizationRepository.saveAndFlush(organization);
    }
}
