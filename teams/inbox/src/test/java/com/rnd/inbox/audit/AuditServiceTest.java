package com.rnd.inbox.audit;

import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.model.User;
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
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        UUID entityId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Map<String, Object> newValues = Map.of("title", "Fix flaky test");

        auditService.createAuditLog("TASK_CREATED", "Task", entityId, tenant, newValues, correlationId);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog audit = captor.getValue();
        assertThat(audit.getAction()).isEqualTo("TASK_CREATED");
        assertThat(audit.getEntityType()).isEqualTo("Task");
        assertThat(audit.getEntityId()).isEqualTo(entityId);
        assertThat(audit.getTenant()).isEqualTo(tenant);
        assertThat(audit.getNewValues()).isEqualTo(newValues);
        assertThat(audit.getCorrelationId()).isEqualTo(correlationId);
        assertThat(audit.getCreatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void createAuditLog_withoutActor_leavesActorNull() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        Map<String, Object> newValues = Map.of("title", "Fix flaky test");

        auditService.createAuditLog("TASK_CREATED", "Task", UUID.randomUUID(), tenant, newValues, UUID.randomUUID());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getActor()).isNull();
    }

    @Test
    void createAuditLog_withActor_setsActorOnTheAuditLog() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        User actor = new User();
        actor.setId(UUID.randomUUID());
        Map<String, Object> newValues = Map.of("status", "DONE");

        auditService.createAuditLog("TASK_COMPLETED", "Task", UUID.randomUUID(), tenant, actor,
                newValues, UUID.randomUUID());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getActor()).isEqualTo(actor);
    }
}
