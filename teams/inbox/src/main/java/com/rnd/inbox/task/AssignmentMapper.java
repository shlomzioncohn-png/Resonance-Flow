package com.rnd.inbox.task;

import com.rnd.inbox.team.Team;
import com.rnd.inbox.user.User;
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
        Assignment assignment = new Assignment();
        assignment.setUser(user);
        assignment.setTeam(team);
        assignment.setAssignmentRole(AssignmentRole.valueOf(dto.getAssignmentRole()));
        assignment.setAssignedAt(Instant.now(clock));
        assignment.setAssignedBy(assignedBy);
        return assignment;
    }
}
