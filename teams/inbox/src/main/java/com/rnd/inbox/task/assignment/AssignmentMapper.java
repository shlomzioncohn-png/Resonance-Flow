package com.rnd.inbox.task.assignment;
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
        Assignment assignment = new Assignment();
        assignment.setUser(user);
        assignment.setTeam(team);
        assignment.setAssignmentRole(AssignmentRole.valueOf(dto.getAssignmentRole()));
        assignment.setAssignedAt(Instant.now(clock));
        assignment.setAssignedBy(assignedBy);
        return assignment;
    }
}
