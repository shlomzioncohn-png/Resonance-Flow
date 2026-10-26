package com.rnd.inbox.task.assignment;
import com.rnd.inbox.task.dto.AssignmentResponseDto;
import com.rnd.inbox.task.dto.TaskAssigneeRequestDto;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.user.model.User;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class AssignmentMapper {

    private final Clock clock;

    public AssignmentMapper(Clock clock) {
        this.clock = clock;
    }

    public Assignment mapAssignment(AssigneeDto dto, User user, Team team, User assignedBy) {
        return mapAssignment(AssignmentRole.valueOf(dto.getAssignmentRole()), user, team, assignedBy);
    }

    public Assignment mapAssignment(TaskAssigneeRequestDto dto, User user, Team team, User assignedBy) {
        return mapAssignment(AssignmentRole.valueOf(dto.getAssignmentRole()), user, team, assignedBy);
    }

    public AssignmentResponseDto toResponseDto(Assignment assignment) {
        boolean isUser = assignment.getUser() != null;
        return new AssignmentResponseDto(
                isUser ? AssigneeType.USER : AssigneeType.TEAM,
                isUser ? assignment.getUser().getId() : assignment.getTeam().getId(),
                assignment.getAssignmentRole(),
                assignment.getAssignedAt(),
                assignment.getAssignedBy() != null ? assignment.getAssignedBy().getId() : null);
    }

    private Assignment mapAssignment(AssignmentRole role, User user, Team team, User assignedBy) {
        Assignment assignment = new Assignment();
        assignment.setUser(user);
        assignment.setTeam(team);
        assignment.setAssignmentRole(role);
        assignment.setAssignedAt(Instant.now(clock));
        assignment.setAssignedBy(assignedBy);
        return assignment;
    }
}
