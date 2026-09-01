package com.rnd.inbox.bridge.task;

import com.rnd.inbox.model.entity.Assignment;
import com.rnd.inbox.model.entity.Organization;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.model.entity.Team;
import com.rnd.inbox.model.entity.User;
import com.rnd.inbox.model.enums.AssignmentRole;
import com.rnd.inbox.model.enums.SourceType;
import com.rnd.inbox.model.enums.TaskExtractionProvenance;
import com.rnd.inbox.model.enums.TaskStatus;
import com.rnd.inbox.repository.OrganizationRepository;
import com.rnd.inbox.repository.ProjectRepository;
import com.rnd.inbox.repository.TeamRepository;
import com.rnd.inbox.repository.UserRepository;
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
class BridgeTaskMapperTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TeamRepository teamRepository;

    private final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    @Test
    void mapsDtoToTaskWithoutTakingIdFromThePayload() {
        UUID organizationId = UUID.randomUUID();
        Organization organizationRef = new Organization();
        organizationRef.setId(organizationId);
        when(organizationRepository.getReferenceById(organizationId)).thenReturn(organizationRef);

        BridgeTaskEventDto dto = new BridgeTaskEventDto();
        dto.setOrganizationId(organizationId.toString());
        dto.setTitle("Fix flaky test");
        dto.setDescription("Investigate intermittent CI failure");
        dto.setSourceType("GITHUB_ISSUE");
        dto.setSourceRef("github:owner/repo:issue:42");
        dto.setStatus("IN_REVIEW");
        dto.setUrgency(4);
        dto.setConfidence(0.9);

        BridgeTaskMapper mapper = mapper();
        Task task = mapper.map(dto);

        assertThat(task.getId()).isNull();
        assertThat(task.getOrganization()).isSameAs(organizationRef);
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
        UUID organizationId = UUID.randomUUID();
        Organization organizationRef = new Organization();
        organizationRef.setId(organizationId);
        when(organizationRepository.getReferenceById(organizationId)).thenReturn(organizationRef);

        BridgeTaskEventDto dto = new BridgeTaskEventDto();
        dto.setOrganizationId(organizationId.toString());
        dto.setTitle("Fix flaky test");
        dto.setSourceType("GITHUB_ISSUE");
        dto.setSourceRef("github:owner/repo:issue:42");

        BridgeTaskMapper mapper = mapper();
        Task task = mapper.map(dto);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.NEW);
    }

    @Test
    void mapAssignment_whenNoAssigneeIsPresent_returnsNull() {
        BridgeTaskEventDto dto = new BridgeTaskEventDto();

        Assignment assignment = mapper().mapAssignment(dto);

        assertThat(assignment).isNull();
    }

    @Test
    void mapAssignment_withAssigneeUserId_buildsAnAssignmentToThatUser() {
        UUID userId = UUID.randomUUID();
        User userRef = new User();
        userRef.setId(userId);
        when(userRepository.getReferenceById(userId)).thenReturn(userRef);

        BridgeTaskEventDto dto = new BridgeTaskEventDto();
        dto.setAssigneeUserId(userId.toString());
        dto.setAssignmentRole("ASSIGNEE");

        Assignment assignment = mapper().mapAssignment(dto);

        assertThat(assignment.getUser()).isSameAs(userRef);
        assertThat(assignment.getTeam()).isNull();
        assertThat(assignment.getAssignmentRole()).isEqualTo(AssignmentRole.ASSIGNEE);
        assertThat(assignment.getAssignedAt()).isEqualTo(FIXED_NOW);
        assertThat(assignment.getAssignedBy()).isNull();
    }

    @Test
    void mapAssignment_withAssigneeTeamId_buildsAnAssignmentToThatTeam() {
        UUID teamId = UUID.randomUUID();
        Team teamRef = new Team();
        teamRef.setId(teamId);
        when(teamRepository.getReferenceById(teamId)).thenReturn(teamRef);

        BridgeTaskEventDto dto = new BridgeTaskEventDto();
        dto.setAssigneeTeamId(teamId.toString());
        dto.setAssignmentRole("OWNER");

        Assignment assignment = mapper().mapAssignment(dto);

        assertThat(assignment.getTeam()).isSameAs(teamRef);
        assertThat(assignment.getUser()).isNull();
        assertThat(assignment.getAssignmentRole()).isEqualTo(AssignmentRole.OWNER);
    }

    @Test
    void mapAssignment_withAssignedByUserId_setsWhoPerformedTheAssignment() {
        UUID userId = UUID.randomUUID();
        UUID assignedByUserId = UUID.randomUUID();
        User userRef = new User();
        userRef.setId(userId);
        User assignedByRef = new User();
        assignedByRef.setId(assignedByUserId);
        when(userRepository.getReferenceById(userId)).thenReturn(userRef);
        when(userRepository.getReferenceById(assignedByUserId)).thenReturn(assignedByRef);

        BridgeTaskEventDto dto = new BridgeTaskEventDto();
        dto.setAssigneeUserId(userId.toString());
        dto.setAssignmentRole("ASSIGNEE");
        dto.setAssignedByUserId(assignedByUserId.toString());

        Assignment assignment = mapper().mapAssignment(dto);

        assertThat(assignment.getAssignedBy()).isSameAs(assignedByRef);
    }

    private BridgeTaskMapper mapper() {
        return new BridgeTaskMapper(organizationRepository, projectRepository, userRepository, teamRepository, clock);
    }
}
