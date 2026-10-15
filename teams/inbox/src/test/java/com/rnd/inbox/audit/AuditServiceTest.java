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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

        auditService.createAuditLog("TASK_CREATED", "Task", entityId, tenant, null, newValues, correlationId);

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
    void createAuditLog_withNullActor_leavesActorNull() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        Map<String, Object> newValues = Map.of("title", "Fix flaky test");

        auditService.createAuditLog("TASK_CREATED", "Task", UUID.randomUUID(), tenant, null, newValues,
                UUID.randomUUID());

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

    @Test
    void createUpdateAuditLog_whenOneFieldChanged_storesOnlyThatFieldInOldValuesAndFullSnapshotInNewValues() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        User actor = new User();
        actor.setId(UUID.randomUUID());
        UUID entityId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Map<String, Object> before = snapshot("Fix flaky test", "NEW");
        Map<String, Object> after = snapshot("Fix flaky test", "IN_PROGRESS");

        auditService.createUpdateAuditLog("TASK_STATUS_UPDATED", "Task", entityId, tenant, actor,
                before, after, correlationId);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog audit = captor.getValue();
        assertThat(audit.getOldValues()).containsOnly(Map.entry("status", "NEW"));
        assertThat(audit.getNewValues()).isEqualTo(after);
        assertThat(audit.getAction()).isEqualTo("TASK_STATUS_UPDATED");
        assertThat(audit.getEntityType()).isEqualTo("Task");
        assertThat(audit.getEntityId()).isEqualTo(entityId);
        assertThat(audit.getTenant()).isEqualTo(tenant);
        assertThat(audit.getActor()).isEqualTo(actor);
        assertThat(audit.getCorrelationId()).isEqualTo(correlationId);
        assertThat(audit.getCreatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void createUpdateAuditLog_whenNothingChanged_savesNoAuditLog() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());

        auditService.createUpdateAuditLog("TASK_UPDATED", "Task", UUID.randomUUID(), tenant, null,
                snapshot("Fix flaky test", "NEW"), snapshot("Fix flaky test", "NEW"), UUID.randomUUID());

        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void createUpdateAuditLog_whenFieldWasNullBefore_storesItAsNullInOldValues() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        Map<String, Object> before = snapshot("Fix flaky test", "NEW");
        before.put("deadline", null);
        Map<String, Object> after = snapshot("Fix flaky test", "NEW");
        after.put("deadline", "2026-02-01T00:00:00Z");

        auditService.createUpdateAuditLog("TASK_UPDATED", "Task", UUID.randomUUID(), tenant, null,
                before, after, UUID.randomUUID());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getOldValues()).hasSize(1).containsEntry("deadline", null);
    }

    @Test
    void createUpdateAuditLog_whenFieldExistsOnlyAfter_savesTheRowButLeavesItOutOfOldValues() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        Map<String, Object> before = snapshot("Fix flaky test", "REJECTED");
        Map<String, Object> after = snapshot("Fix flaky test", "REJECTED");
        after.put("feedbackType", "NEEDS_CLARIFICATION");

        auditService.createUpdateAuditLog("TASK_STATUS_UPDATED", "Task", UUID.randomUUID(), tenant, null,
                before, after, UUID.randomUUID());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getOldValues()).isEmpty();
        assertThat(captor.getValue().getNewValues()).isEqualTo(after);
    }

    // Map.of rejects null values, and a snapshot may legitimately hold one (e.g. no deadline).
    private static Map<String, Object> snapshot(String title, String status) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", title);
        snapshot.put("status", status);
        return snapshot;
    }
}
