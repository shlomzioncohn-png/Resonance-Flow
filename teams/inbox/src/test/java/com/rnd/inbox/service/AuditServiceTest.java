package com.rnd.inbox.service;

import com.rnd.inbox.model.entity.AuditLog;
import com.rnd.inbox.model.entity.Organization;
import com.rnd.inbox.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService(auditLogRepository, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void record_savesAnAuditLogBuiltFromTheGivenFields() {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        UUID entityId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Map<String, Object> newValues = Map.of("title", "Fix flaky test");

        auditService.createAuditLog("TASK_CREATED", "Task", entityId, organization, newValues, correlationId);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog audit = captor.getValue();
        assertThat(audit.getAction()).isEqualTo("TASK_CREATED");
        assertThat(audit.getEntityType()).isEqualTo("Task");
        assertThat(audit.getEntityId()).isEqualTo(entityId);
        assertThat(audit.getOrganization()).isEqualTo(organization);
        assertThat(audit.getNewValues()).isEqualTo(newValues);
        assertThat(audit.getCorrelationId()).isEqualTo(correlationId);
        assertThat(audit.getCreatedAt()).isEqualTo(FIXED_NOW);
    }
}
