package com.rnd.inbox.structuredtask;

import com.rnd.inbox.project.Project;
import com.rnd.inbox.task.SourceType;
import com.rnd.inbox.task.Task;
import com.rnd.inbox.task.TaskExtractionProvenance;
import com.rnd.inbox.task.TaskStatus;
import com.rnd.inbox.tenant.Tenant;
import com.rnd.inbox.user.User;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredTaskMapperTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    @Test
    void mapsDtoToTaskWithoutTakingIdFromThePayload() {
        Tenant tenantRef = tenant();

        StructuredTaskEventDto dto = new StructuredTaskEventDto();
        dto.setTenantId(tenantRef.getId().toString());
        dto.setTitle("Fix flaky test");
        dto.setDescription("Investigate intermittent CI failure");
        dto.setSourceType("GITHUB_ISSUE");
        dto.setSourceRef("github:owner/repo:issue:42");
        dto.setStatus("IN_REVIEW");
        dto.setUrgency(4);
        dto.setConfidence(0.9);

        Task task = mapper().map(dto, references(tenantRef, null, null));

        assertThat(task.getId()).isNull();
        assertThat(task.getTenant()).isSameAs(tenantRef);
        assertThat(task.getTitle()).isEqualTo("Fix flaky test");
        assertThat(task.getSourceType()).isEqualTo(SourceType.GITHUB_ISSUE);
        assertThat(task.getSourceRef()).isEqualTo("github:owner/repo:issue:42");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_REVIEW);
        assertThat(task.getExtractionProvenance()).isEqualTo(TaskExtractionProvenance.ETL);
        assertThat(task.getUrgency()).isEqualTo((short) 4);
        assertThat(task.getConfidence()).isEqualTo(0.9f);
        assertThat(task.isAvailable()).isTrue();
        assertThat(task.getCreatedAt()).isEqualTo(FIXED_NOW);
        assertThat(task.getUpdatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void defaultsStatusToNewWhenAbsent() {
        Tenant tenantRef = tenant();

        StructuredTaskEventDto dto = new StructuredTaskEventDto();
        dto.setTenantId(tenantRef.getId().toString());
        dto.setTitle("Fix flaky test");
        dto.setSourceType("GITHUB_ISSUE");
        dto.setSourceRef("github:owner/repo:issue:42");

        Task task = mapper().map(dto, references(tenantRef, null, null));

        assertThat(task.getStatus()).isEqualTo(TaskStatus.NEW);
    }

    @Test
    void resolvesTheProjectReferenceOnlyWhenThePayloadCarriesOne() {
        Tenant tenantRef = tenant();
        Project projectRef = new Project();
        projectRef.setId(UUID.randomUUID());

        StructuredTaskEventDto dto = new StructuredTaskEventDto();
        dto.setTenantId(tenantRef.getId().toString());
        dto.setTitle("Fix flaky test");
        dto.setSourceType("GITHUB_ISSUE");
        dto.setSourceRef("github:owner/repo:issue:42");

        Task task = mapper().map(dto, references(tenantRef, projectRef, null));

        assertThat(task.getProject()).isSameAs(projectRef);
    }

    @Test
    void mapsCreatedByWhenTheReferencesCarryACreator() {
        Tenant tenantRef = tenant();
        User createdByRef = user();

        StructuredTaskEventDto dto = new StructuredTaskEventDto();
        dto.setTenantId(tenantRef.getId().toString());
        dto.setTitle("Fix flaky test");
        dto.setSourceType("GITHUB_ISSUE");
        dto.setSourceRef("github:owner/repo:issue:42");

        Task task = mapper().map(dto, references(tenantRef, null, createdByRef));

        assertThat(task.getCreatedBy()).isSameAs(createdByRef);
    }

    @Test
    void leavesCreatedByUnsetWhenTheReferencesOmitIt() {
        Tenant tenantRef = tenant();

        StructuredTaskEventDto dto = new StructuredTaskEventDto();
        dto.setTenantId(tenantRef.getId().toString());
        dto.setTitle("Fix flaky test");
        dto.setSourceType("GITHUB_ISSUE");
        dto.setSourceRef("github:owner/repo:issue:42");

        Task task = mapper().map(dto, references(tenantRef, null, null));

        assertThat(task.getCreatedBy()).isNull();
    }

    private StructuredTaskMapper mapper() {
        return new StructuredTaskMapper(clock);
    }

    private StructuredTaskReferences references(Tenant tenant, Project project, User createdBy) {
        return new StructuredTaskReferences(tenant, project, createdBy);
    }

    private Tenant tenant() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        return tenant;
    }

    private User user() {
        User user = new User();
        user.setId(UUID.randomUUID());
        return user;
    }
}
