package com.rnd.inbox.task.mapper;
import com.rnd.inbox.task.assignment.AssignmentMapper;
import com.rnd.inbox.task.dto.TaskCreateRequestDto;
import com.rnd.inbox.task.dto.TaskResponseDto;
import com.rnd.inbox.task.dto.TaskUpdateRequestDto;
import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.model.TaskExtractionProvenance;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.project.Project;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRestMapperTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    private TaskRestMapper taskRestMapper;
    private Tenant tenant;
    private Project project;
    private User owner;
    private User actor;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        taskRestMapper = new TaskRestMapper(clock, new AssignmentMapper(clock));

        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Acme Inc");
        tenant.setSlug("acme");

        project = new Project();
        project.setId(UUID.randomUUID());
        project.setTenant(tenant);
        project.setName("Platform Service");

        owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setDisplayName("Yael Cohen");

        actor = new User();
        actor.setId(UUID.randomUUID());
    }

    private TaskCreateRequestDto createRequest() {
        TaskCreateRequestDto request = new TaskCreateRequestDto();
        request.setTenantId(tenant.getId().toString());
        request.setTitle("Fix flaky test");
        request.setDescription("The CI job fails intermittently");
        request.setUrgency((short) 3);
        request.setEffortEstimateMinutes(45);
        request.setDeadline(Instant.parse("2026-02-01T00:00:00Z"));
        return request;
    }

    @Test
    void toEntity_buildsTaskFromCreateRequest_withManualDefaults() {
        Task task = taskRestMapper.toEntity(createRequest(), tenant, project, owner, actor);

        assertThat(task.getTenant()).isSameAs(tenant);
        assertThat(task.getProject()).isSameAs(project);
        assertThat(task.getTitle()).isEqualTo("Fix flaky test");
        assertThat(task.getDescription()).isEqualTo("The CI job fails intermittently");
        assertThat(task.getOwner()).isSameAs(owner);
        assertThat(task.getCreatedBy()).isSameAs(actor);
        assertThat(task.getUrgency()).isEqualTo((short) 3);
        assertThat(task.getEffortEstimateMinutes()).isEqualTo(45);
        assertThat(task.getSourceType()).isEqualTo(SourceType.MANUAL);
        assertThat(task.getExtractionProvenance()).isEqualTo(TaskExtractionProvenance.MANUAL);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.NEW);
        assertThat(task.isAvailable()).isFalse();
        assertThat(task.getCreatedAt()).isEqualTo(FIXED_NOW);
        assertThat(task.getUpdatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void applyUpdate_overwritesProvidedFieldsAndBumpsUpdatedAt() {
        Task task = new Task();
        task.setTitle("Old title");
        task.setStatus(TaskStatus.NEW);
        task.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        task.setUpdatedAt(Instant.parse("2025-01-01T00:00:00Z"));

        TaskUpdateRequestDto request = new TaskUpdateRequestDto();
        request.setTitle("New title");
        request.setDescription(JsonNullable.of("New description"));
        request.setProjectId(JsonNullable.of(project.getId().toString()));
        request.setOwnerId(JsonNullable.of(owner.getId().toString()));
        request.setUrgency(JsonNullable.of((short) 5));
        request.setEffortEstimateMinutes(JsonNullable.of(90));
        request.setDeadline(JsonNullable.of(Instant.parse("2026-03-01T00:00:00Z")));

        taskRestMapper.applyUpdate(task, request, project, owner);

        assertThat(task.getTitle()).isEqualTo("New title");
        assertThat(task.getDescription()).isEqualTo("New description");
        assertThat(task.getProject()).isSameAs(project);
        assertThat(task.getOwner()).isSameAs(owner);
        assertThat(task.getUrgency()).isEqualTo((short) 5);
        assertThat(task.getEffortEstimateMinutes()).isEqualTo(90);
        assertThat(task.getUpdatedAt()).isEqualTo(FIXED_NOW);

        // applyUpdate must never touch these
        assertThat(task.getStatus()).isEqualTo(TaskStatus.NEW);
        assertThat(task.getCreatedAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
    }

    @Test
    void applyUpdate_whenFieldIsOmitted_preservesItsExistingValue() {
        Task task = new Task();
        task.setTitle("Existing title");
        task.setDescription("Existing description");
        task.setProject(project);
        task.setOwner(owner);

        TaskUpdateRequestDto request = new TaskUpdateRequestDto();
        request.setTitle("New title");
        // description, projectId, ownerId intentionally left undefined - not part of this update

        taskRestMapper.applyUpdate(task, request, null, null);

        assertThat(task.getTitle()).isEqualTo("New title");
        assertThat(task.getDescription()).isEqualTo("Existing description");
        assertThat(task.getProject()).isSameAs(project);
        assertThat(task.getOwner()).isSameAs(owner);
    }

    @Test
    void applyUpdate_whenFieldIsExplicitlyNull_clearsItRatherThanPreservingIt() {
        Task task = new Task();
        task.setDescription("Existing description");
        task.setProject(project);
        task.setDeadline(Instant.parse("2026-01-15T00:00:00Z"));

        TaskUpdateRequestDto request = new TaskUpdateRequestDto();
        request.setDescription(JsonNullable.of(null));
        request.setProjectId(JsonNullable.of(null));
        request.setDeadline(JsonNullable.of(null));

        taskRestMapper.applyUpdate(task, request, null, null);

        assertThat(task.getDescription()).isNull();
        assertThat(task.getProject()).isNull();
        assertThat(task.getDeadline()).isNull();
    }

    @Test
    void applyStatusChange_toDone_setsCompletedAt() {
        Task task = new Task();
        task.setStatus(TaskStatus.IN_PROGRESS);

        taskRestMapper.applyStatusChange(task, TaskStatus.DONE, null);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task.getCompletedAt()).isEqualTo(FIXED_NOW);
        assertThat(task.getUpdatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void applyStatusChange_toSnoozed_setsSnoozedUntilAndLeavesCompletedAtNull() {
        Task task = new Task();
        task.setStatus(TaskStatus.NEW);
        Instant snoozedUntil = Instant.parse("2026-02-01T00:00:00Z");

        taskRestMapper.applyStatusChange(task, TaskStatus.SNOOZED, snoozedUntil);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.SNOOZED);
        assertThat(task.getSnoozedUntil()).isEqualTo(snoozedUntil);
        assertThat(task.getCompletedAt()).isNull();
    }

    @Test
    void toResponseDto_mapsAllFieldsIncludingNestedTenantSummary() {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setTenant(tenant);
        task.setProject(project);
        task.setTitle("Fix flaky test");
        task.setSourceType(SourceType.MANUAL);
        task.setStatus(TaskStatus.NEW);
        task.setAvailable(false);
        task.setExtractionProvenance(TaskExtractionProvenance.MANUAL);
        task.setOwner(owner);
        task.setCreatedBy(actor);
        task.setCreatedAt(FIXED_NOW);
        task.setUpdatedAt(FIXED_NOW);

        TaskResponseDto dto = taskRestMapper.toResponseDto(task, List.of());

        assertThat(dto.getId()).isEqualTo(task.getId());
        assertThat(dto.getTenant().getId()).isEqualTo(tenant.getId());
        assertThat(dto.getTenant().getSlug()).isEqualTo("acme");
        assertThat(dto.getProjectId()).isEqualTo(project.getId());
        assertThat(dto.getProjectName()).isEqualTo("Platform Service");
        assertThat(dto.getTitle()).isEqualTo("Fix flaky test");
        assertThat(dto.getStatus()).isEqualTo(TaskStatus.NEW);
        assertThat(dto.getOwnerId()).isEqualTo(owner.getId());
        assertThat(dto.getOwnerDisplayName()).isEqualTo("Yael Cohen");
        assertThat(dto.getCreatedById()).isEqualTo(actor.getId());
    }

    @Test
    void toResponseDto_whenProjectAndOwnerAreNull_idsAndNamesAreNull() {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setTenant(tenant);
        task.setSourceType(SourceType.MANUAL);
        task.setStatus(TaskStatus.NEW);
        task.setExtractionProvenance(TaskExtractionProvenance.MANUAL);

        TaskResponseDto dto = taskRestMapper.toResponseDto(task, List.of());

        assertThat(dto.getProjectId()).isNull();
        assertThat(dto.getProjectName()).isNull();
        assertThat(dto.getOwnerId()).isNull();
        assertThat(dto.getOwnerDisplayName()).isNull();
        assertThat(dto.getCreatedById()).isNull();
    }
}
