package com.rnd.inbox.taskcreationrequest;

import com.rnd.inbox.project.Project;
import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.model.TaskExtractionProvenance;
import com.rnd.inbox.task.security.TaskRoleContext;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.model.TaskTier;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.model.User;
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

        Task task = mapper().map(dto, tenantRef, null, null, null);

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

        Task task = mapper().map(dtoFor(tenantRef.getId()), tenantRef, null, null, null);

        assertThat(task.getExtractionProvenance()).isEqualTo(TaskExtractionProvenance.ML_INFERRED);
    }

    @Test
    void defaultsStatusToNewWhenThePayloadOmitsIt() {
        Tenant tenantRef = tenant();

        Task task = mapper().map(dtoFor(tenantRef.getId()), tenantRef, null, null, null);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.NEW);
    }

    @Test
    void mapsATaskWithoutDependenciesAsImmediatelyAvailable() {
        Tenant tenantRef = tenant();
        TaskCreationRequestEventDto dto = dtoFor(tenantRef.getId());
        dto.setHasDependencies(false);

        assertThat(mapper().map(dto, tenantRef, null, null, null).isAvailable()).isTrue();
    }

    @Test
    void mapsATaskWithDependenciesAsBlockedUntilChainCalculatesAvailability() {
        Tenant tenantRef = tenant();
        TaskCreationRequestEventDto dto = dtoFor(tenantRef.getId());
        dto.setHasDependencies(true);

        assertThat(mapper().map(dto, tenantRef, null, null, null).isAvailable()).isFalse();
    }

    @Test
    void resolvesTheProjectReferenceOnlyWhenThePayloadCarriesOne() {
        Tenant tenantRef = tenant();
        Project projectRef = new Project();
        projectRef.setId(UUID.randomUUID());

        TaskCreationRequestEventDto dto = dtoFor(tenantRef.getId());
        dto.setProjectId(projectRef.getId().toString());

        assertThat(mapper().map(dto, tenantRef, projectRef, null, null).getProject()).isSameAs(projectRef);
    }

    @Test
    void leavesTheProjectUnsetWhenThePayloadOmitsIt() {
        Tenant tenantRef = tenant();

        assertThat(mapper().map(dtoFor(tenantRef.getId()), tenantRef, null, null, null).getProject()).isNull();
    }

    @Test
    void mapsCreatedByWhenACreatorIsProvided() {
        Tenant tenantRef = tenant();
        User createdByRef = new User();
        createdByRef.setId(UUID.randomUUID());

        Task task = mapper().map(dtoFor(tenantRef.getId()), tenantRef, null, createdByRef, null);

        assertThat(task.getCreatedBy()).isSameAs(createdByRef);
    }

    @Test
    void leavesCreatedByUnsetWhenNoCreatorIsProvided() {
        Tenant tenantRef = tenant();

        Task task = mapper().map(dtoFor(tenantRef.getId()), tenantRef, null, null, null);

        assertThat(task.getCreatedBy()).isNull();
    }

    @Test
    void mapsTheSuggestionFieldsWhenProvided() {
        Tenant tenantRef = tenant();
        User suggestedOwnerRef = new User();
        suggestedOwnerRef.setId(UUID.randomUUID());

        TaskCreationRequestEventDto dto = dtoFor(tenantRef.getId());
        dto.setEffortEstimateConfidence(0.6);
        dto.setSuggestedOwnerUserId(suggestedOwnerRef.getId().toString());
        dto.setSuggestedOwnerConfidence(0.8);

        Task task = mapper().map(dto, tenantRef, null, null, suggestedOwnerRef);

        assertThat(task.getEffortEstimateConfidence()).isEqualTo(0.6f);
        assertThat(task.getSuggestedOwnerUser()).isSameAs(suggestedOwnerRef);
        assertThat(task.getSuggestedOwnerConfidence()).isEqualTo(0.8f);
    }

    @Test
    void leavesTheSuggestionFieldsUnsetWhenNotProvided() {
        Tenant tenantRef = tenant();

        Task task = mapper().map(dtoFor(tenantRef.getId()), tenantRef, null, null, null);

        assertThat(task.getEffortEstimateConfidence()).isNull();
        assertThat(task.getSuggestedOwnerUser()).isNull();
        assertThat(task.getSuggestedOwnerConfidence()).isNull();
    }

    @Test
    void doesNotTurnTheSuggestedOwnerIntoTheOwner() {
        Tenant tenantRef = tenant();
        User suggestedOwnerRef = new User();
        suggestedOwnerRef.setId(UUID.randomUUID());

        TaskCreationRequestEventDto dto = dtoFor(tenantRef.getId());
        dto.setSuggestedOwnerUserId(suggestedOwnerRef.getId().toString());

        Task task = mapper().map(dto, tenantRef, null, null, suggestedOwnerRef);

        assertThat(task.getOwner()).isNull();
    }

}
