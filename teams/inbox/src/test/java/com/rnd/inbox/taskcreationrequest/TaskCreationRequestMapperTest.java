package com.rnd.inbox.taskcreationrequest;

import com.rnd.inbox.project.Project;
import com.rnd.inbox.task.SourceType;
import com.rnd.inbox.task.Task;
import com.rnd.inbox.task.TaskExtractionProvenance;
import com.rnd.inbox.task.TaskRoleContext;
import com.rnd.inbox.task.TaskStatus;
import com.rnd.inbox.task.TaskTier;
import com.rnd.inbox.tenant.Tenant;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskCreationRequestMapperTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private TaskCreationRequestMapper mapper() {
        return new TaskCreationRequestMapper(clock);
    }

    private TaskCreationRequestEventDto dtoFor(UUID tenantId) {
        TaskCreationRequestEventDto dto = new TaskCreationRequestEventDto();
        dto.setEventId(UUID.randomUUID().toString());
        dto.setCorrelationId(UUID.randomUUID().toString());
        dto.setTenantId(tenantId.toString());
        dto.setTitle("Send the revised pricing deck to Acme");
        dto.setSourceType("SLACK");
        dto.setSourceRef("slack:T012AB3CD:C024BE91L:1756550400.001900");
        dto.setConfidence(0.87);
        dto.setHasDependencies(false);
        return dto;
    }

    private Tenant tenant() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        return tenant;
    }

    @Test
    void mapsDtoToTaskWithoutTakingIdFromThePayload() {
        Tenant tenantRef = tenant();

        TaskCreationRequestEventDto dto = dtoFor(tenantRef.getId());
        dto.setDescription("Extracted from the #sales-acme thread");
        dto.setTier("TACTICAL");
        dto.setRoleContext("SALESPERSON");
        dto.setUrgency(4);
        dto.setEffortEstimateMinutes(45);
        dto.setDeadline("2026-09-02T12:00:00Z");

        Task task = mapper().map(dto, tenantRef, null);

        assertThat(task.getId()).isNull();
        assertThat(task.getTenant()).isSameAs(tenantRef);
        assertThat(task.getTitle()).isEqualTo("Send the revised pricing deck to Acme");
        assertThat(task.getSourceType()).isEqualTo(SourceType.SLACK);
        assertThat(task.getSourceRef()).isEqualTo("slack:T012AB3CD:C024BE91L:1756550400.001900");
        assertThat(task.getTier()).isEqualTo(TaskTier.TACTICAL);
        assertThat(task.getRoleContext()).isEqualTo(TaskRoleContext.SALESPERSON);
        assertThat(task.getUrgency()).isEqualTo((short) 4);
        assertThat(task.getEffortEstimateMinutes()).isEqualTo(45);
        assertThat(task.getDeadline()).isEqualTo(Instant.parse("2026-09-02T12:00:00Z"));
        assertThat(task.getConfidence()).isEqualTo(0.87f);
        assertThat(task.getCreatedAt()).isEqualTo(FIXED_NOW);
        assertThat(task.getUpdatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void mapsTheUnstructuredPathAsMlInferredRatherThanEtl() {
        Tenant tenantRef = tenant();

        Task task = mapper().map(dtoFor(tenantRef.getId()), tenantRef, null);

        assertThat(task.getExtractionProvenance()).isEqualTo(TaskExtractionProvenance.ML_INFERRED);
    }

    @Test
    void defaultsStatusToNewWhenThePayloadOmitsIt() {
        Tenant tenantRef = tenant();

        Task task = mapper().map(dtoFor(tenantRef.getId()), tenantRef, null);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.NEW);
    }

    @Test
    void mapsATaskWithoutDependenciesAsImmediatelyAvailable() {
        Tenant tenantRef = tenant();
        TaskCreationRequestEventDto dto = dtoFor(tenantRef.getId());
        dto.setHasDependencies(false);

        assertThat(mapper().map(dto, tenantRef, null).isAvailable()).isTrue();
    }

    @Test
    void mapsATaskWithDependenciesAsBlockedUntilChainCalculatesAvailability() {
        Tenant tenantRef = tenant();
        TaskCreationRequestEventDto dto = dtoFor(tenantRef.getId());
        dto.setHasDependencies(true);

        assertThat(mapper().map(dto, tenantRef, null).isAvailable()).isFalse();
    }

    @Test
    void resolvesTheProjectReferenceOnlyWhenThePayloadCarriesOne() {
        Tenant tenantRef = tenant();
        Project projectRef = new Project();
        projectRef.setId(UUID.randomUUID());

        TaskCreationRequestEventDto dto = dtoFor(tenantRef.getId());
        dto.setProjectId(projectRef.getId().toString());

        assertThat(mapper().map(dto, tenantRef, projectRef).getProject()).isSameAs(projectRef);
    }

    @Test
    void leavesTheProjectUnsetWhenThePayloadOmitsIt() {
        Tenant tenantRef = tenant();

        assertThat(mapper().map(dtoFor(tenantRef.getId()), tenantRef, null).getProject()).isNull();
    }
}
