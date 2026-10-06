package com.rnd.inbox.task.assignment;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.user.model.User;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentMapperTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private AssignmentMapper mapper() {
        return new AssignmentMapper(clock);
    }

    private AssigneeDto assigneeDto(String assignmentRole) {
        AssigneeDto dto = new AssigneeDto();
        dto.setAssigneeType("USER");
        dto.setId(UUID.randomUUID().toString());
        dto.setAssignmentRole(assignmentRole);
        return dto;
    }

    private User user() {
        User user = new User();
        user.setId(UUID.randomUUID());
        return user;
    }

    @Test
    void mapAssignment_setsTheResolvedUserAndRole() {
        User userRef = user();
        AssigneeDto dto = assigneeDto("OWNER");

        Assignment assignment = mapper().mapAssignment(dto, userRef, null, null);

        assertThat(assignment.getUser()).isSameAs(userRef);
        assertThat(assignment.getTeam()).isNull();
        assertThat(assignment.getAssignmentRole()).isEqualTo(AssignmentRole.OWNER);
        assertThat(assignment.getAssignedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void mapAssignment_setsTheResolvedTeam() {
        Team teamRef = new Team();
        teamRef.setId(UUID.randomUUID());
        AssigneeDto dto = assigneeDto("ASSIGNEE");

        Assignment assignment = mapper().mapAssignment(dto, null, teamRef, null);

        assertThat(assignment.getTeam()).isSameAs(teamRef);
        assertThat(assignment.getUser()).isNull();
    }

    @Test
    void mapAssignment_setsAssignedByWhenProvided() {
        User assignedByRef = user();
        AssigneeDto dto = assigneeDto("COLLABORATOR");

        Assignment assignment = mapper().mapAssignment(dto, user(), null, assignedByRef);

        assertThat(assignment.getAssignedBy()).isSameAs(assignedByRef);
    }

    @Test
    void mapAssignment_leavesAssignedByNullWhenNotProvided() {
        AssigneeDto dto = assigneeDto("ASSIGNEE");

        Assignment assignment = mapper().mapAssignment(dto, user(), null, null);

        assertThat(assignment.getAssignedBy()).isNull();
    }
}
