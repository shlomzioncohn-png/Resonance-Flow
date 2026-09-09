package com.rnd.inbox.structuredtask;

import com.rnd.inbox.project.Project;
import com.rnd.inbox.task.Assignment;
import com.rnd.inbox.task.AssignmentRole;
import com.rnd.inbox.task.SourceType;
import com.rnd.inbox.task.Task;
import com.rnd.inbox.task.TaskExtractionProvenance;
import com.rnd.inbox.task.TaskStatus;
import com.rnd.inbox.team.Team;
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

        Task task = mapper().map(dto, references(tenantRef, null, null, null, null));

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

        Task task = mapper().map(dto, references(tenantRef, null, null, null, null));

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

        Task task = mapper().map(dto, references(tenantRef, projectRef, null, null, null));

        assertThat(task.getProject()).isSameAs(projectRef);
    }

    @Test
    void mapAssignment_whenNoAssigneeIsPresent_returnsNull() {
        StructuredTaskEventDto dto = new StructuredTaskEventDto();

        Assignment assignment = mapper().mapAssignment(dto, references(tenant(), null, null, null, null));

        assertThat(assignment).isNull();
    }

    @Test
    void mapAssignment_withAssigneeUserId_buildsAnAssignmentToThatUser() {
        User userRef = user();

        StructuredTaskEventDto dto = new StructuredTaskEventDto();
        dto.setAssigneeUserId(userRef.getId().toString());
        dto.setAssignmentRole("ASSIGNEE");

        Assignment assignment = mapper().mapAssignment(dto, references(tenant(), null, userRef, null, null));

        assertThat(assignment.getUser()).isSameAs(userRef);
        assertThat(assignment.getTeam()).isNull();
        assertThat(assignment.getAssignmentRole()).isEqualTo(AssignmentRole.ASSIGNEE);
        assertThat(assignment.getAssignedAt()).isEqualTo(FIXED_NOW);
        assertThat(assignment.getAssignedBy()).isNull();
    }

    @Test
    void mapAssignment_withAssigneeTeamId_buildsAnAssignmentToThatTeam() {
        Team teamRef = new Team();
        teamRef.setId(UUID.randomUUID());

        StructuredTaskEventDto dto = new StructuredTaskEventDto();
        dto.setAssigneeTeamId(teamRef.getId().toString());
        dto.setAssignmentRole("OWNER");

        Assignment assignment = mapper().mapAssignment(dto, references(tenant(), null, null, teamRef, null));

        assertThat(assignment.getTeam()).isSameAs(teamRef);
        assertThat(assignment.getUser()).isNull();
        assertThat(assignment.getAssignmentRole()).isEqualTo(AssignmentRole.OWNER);
    }

    @Test
    void mapAssignment_withAssignedByUserId_setsWhoPerformedTheAssignment() {
        User userRef = user();
        User assignedByRef = user();

        StructuredTaskEventDto dto = new StructuredTaskEventDto();
        dto.setAssigneeUserId(userRef.getId().toString());
        dto.setAssignmentRole("ASSIGNEE");
        dto.setAssignedByUserId(assignedByRef.getId().toString());

        Assignment assignment = mapper()
                .mapAssignment(dto, references(tenant(), null, userRef, null, assignedByRef));

        assertThat(assignment.getAssignedBy()).isSameAs(assignedByRef);
    }

    private StructuredTaskMapper mapper() {
        return new StructuredTaskMapper(clock);
    }

    private StructuredTaskReferences references(Tenant tenant, Project project, User assignee, Team assigneeTeam,
            User assignedBy) {
        return new StructuredTaskReferences(tenant, project, assignee, assigneeTeam, assignedBy);
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
