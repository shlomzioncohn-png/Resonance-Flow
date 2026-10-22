package com.rnd.inbox.task.assignment;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.task.dto.TaskAssigneeRequestDto;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.team.exception.TeamNotFoundException;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.team.repository.TeamRepository;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.exception.UserNotFoundException;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AssignmentService {

    private final AssignmentRepository assignmentRepository;
    private final AssignmentMapper assignmentMapper;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;

    public AssignmentService(AssignmentRepository assignmentRepository,
                             AssignmentMapper assignmentMapper,
                             UserRepository userRepository,
                             TeamRepository teamRepository) {
        this.assignmentRepository = assignmentRepository;
        this.assignmentMapper = assignmentMapper;
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
    }

    @Transactional(readOnly = true)
    public List<Assignment> resolve(List<TaskAssigneeRequestDto> requested, Tenant tenant, User assignedBy) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        Set<AssigneeKey> seen = new HashSet<>();
        List<Assignment> resolved = new ArrayList<>();
        for (TaskAssigneeRequestDto dto : requested) {
            AssigneeKey key = new AssigneeKey(AssigneeType.valueOf(dto.getAssigneeType()), UUID.fromString(dto.getId()));
            if (!seen.add(key)) {
                throw new DuplicateAssigneeException(key.type(), key.id());
            }
            User user = key.type() == AssigneeType.USER ? findUserInTenant(key.id(), tenant) : null;
            Team team = key.type() == AssigneeType.TEAM ? findTeamInTenant(key.id(), tenant) : null;
            resolved.add(assignmentMapper.mapAssignment(dto, user, team, assignedBy));
        }
        return resolved;
    }

    @Transactional(readOnly = true)
    public List<Assignment> findByTask(UUID taskId) {
        return assignmentRepository.findByTask_Id(taskId);
    }

    @Transactional(readOnly = true)
    public Map<UUID, List<Assignment>> findByTasks(Collection<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return assignmentRepository.findByTask_IdIn(taskIds).stream()
                .collect(Collectors.groupingBy(assignment -> assignment.getTask().getId()));
    }

    @Transactional
    public List<Assignment> replaceAssignments(Task task, List<Assignment> desired) {
        Map<AssigneeKey, Assignment> current = new LinkedHashMap<>();
        for (Assignment existing : assignmentRepository.findByTask_Id(task.getId())) {
            current.put(AssigneeKey.of(existing), existing);
        }

        List<Assignment> result = new ArrayList<>();
        for (Assignment wanted : desired) {
            Assignment existing = current.remove(AssigneeKey.of(wanted));
            if (existing != null) {
                existing.setAssignmentRole(wanted.getAssignmentRole());
                result.add(assignmentRepository.save(existing));
            } else {
                wanted.setTask(task);
                result.add(assignmentRepository.save(wanted));
            }
        }

        assignmentRepository.deleteAll(current.values());
        return result;
    }

    private User findUserInTenant(UUID id, Tenant tenant) {
        User user = userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
        if (!user.getTenant().getId().equals(tenant.getId())) {
            throw new ForbiddenActionException("User " + id + " does not belong to tenant " + tenant.getId());
        }
        return user;
    }

    private Team findTeamInTenant(UUID id, Tenant tenant) {
        Team team = teamRepository.findById(id).orElseThrow(() -> new TeamNotFoundException(id));
        if (!team.getTenant().getId().equals(tenant.getId())) {
            throw new ForbiddenActionException("Team " + id + " does not belong to tenant " + tenant.getId());
        }
        return team;
    }

    private record AssigneeKey(AssigneeType type, UUID id) {

        static AssigneeKey of(Assignment assignment) {
            return assignment.getUser() != null
                    ? new AssigneeKey(AssigneeType.USER, assignment.getUser().getId())
                    : new AssigneeKey(AssigneeType.TEAM, assignment.getTeam().getId());
        }
    }
}
