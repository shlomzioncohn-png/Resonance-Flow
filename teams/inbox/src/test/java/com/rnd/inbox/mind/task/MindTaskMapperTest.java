package com.rnd.inbox.mind.task;

import com.rnd.inbox.model.entity.Tenant;
import com.rnd.inbox.model.entity.Project;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.model.enums.SourceType;
import com.rnd.inbox.model.enums.TaskExtractionProvenance;
import com.rnd.inbox.model.enums.TaskRoleContext;
import com.rnd.inbox.model.enums.TaskStatus;
import com.rnd.inbox.model.enums.TaskTier;
import com.rnd.inbox.repository.TenantRepository;
import com.rnd.inbox.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MindTaskMapperTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private ProjectRepository projectRepository;

    private final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private MindTaskMapper mapper() {
        return new MindTaskMapper(tenantRepository, projectRepository, clock);
    }

    private MindTaskEventDto dtoFor(UUID tenantId) {
        MindTaskEventDto dto = new MindTaskEventDto();
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

    private Tenant stubTenant(UUID tenantId) {
        Tenant tenantRef = new Tenant();
        tenantRef.setId(tenantId);
        when(tenantRepository.getReferenceById(tenantId)).thenReturn(tenantRef);
        return tenantRef;
    }

    @Test
    void mapsDtoToTaskWithoutTakingIdFromThePayload() {
        UUID tenantId = UUID.randomUUID();
        Tenant tenantRef = stubTenant(tenantId);

        MindTaskEventDto dto = dtoFor(tenantId);
        dto.setDescription("Extracted from the #sales-acme thread");
        dto.setTier("TACTICAL");
        dto.setRoleContext("SALESPERSON");
        dto.setUrgency(4);
        dto.setEffortEstimateMinutes(45);
        dto.setDeadline("2026-09-02T12:00:00Z");

        Task task = mapper().map(dto);

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
        UUID tenantId = UUID.randomUUID();
        stubTenant(tenantId);

        Task task = mapper().map(dtoFor(tenantId));

        assertThat(task.getExtractionProvenance()).isEqualTo(TaskExtractionProvenance.ML_INFERRED);
    }

    @Test
    void defaultsStatusToNewWhenThePayloadOmitsIt() {
        UUID tenantId = UUID.randomUUID();
        stubTenant(tenantId);

        Task task = mapper().map(dtoFor(tenantId));

        assertThat(task.getStatus()).isEqualTo(TaskStatus.NEW);
    }

    @Test
    void mapsATaskWithoutDependenciesAsImmediatelyAvailable() {
        UUID tenantId = UUID.randomUUID();
        stubTenant(tenantId);

        MindTaskEventDto dto = dtoFor(tenantId);
        dto.setHasDependencies(false);

        assertThat(mapper().map(dto).isAvailable()).isTrue();
    }

    @Test
    void mapsATaskWithDependenciesAsBlockedUntilChainCalculatesAvailability() {
        UUID tenantId = UUID.randomUUID();
        stubTenant(tenantId);

        MindTaskEventDto dto = dtoFor(tenantId);
        dto.setHasDependencies(true);

        assertThat(mapper().map(dto).isAvailable()).isFalse();
    }

    @Test
    void resolvesTheProjectReferenceOnlyWhenThePayloadCarriesOne() {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        stubTenant(tenantId);
        Project projectRef = new Project();
        projectRef.setId(projectId);
        when(projectRepository.getReferenceById(projectId)).thenReturn(projectRef);

        MindTaskEventDto dto = dtoFor(tenantId);
        dto.setProjectId(projectId.toString());

        assertThat(mapper().map(dto).getProject()).isSameAs(projectRef);
    }

    @Test
    void leavesTheProjectUnsetWhenThePayloadOmitsIt() {
        UUID tenantId = UUID.randomUUID();
        stubTenant(tenantId);

        assertThat(mapper().map(dtoFor(tenantId)).getProject()).isNull();
    }
}
