package com.rnd.inbox.structuredtask;

import com.rnd.inbox.project.ProjectRepository;
import com.rnd.inbox.task.Assignment;
import com.rnd.inbox.task.AssignmentRole;
import com.rnd.inbox.task.SourceType;
import com.rnd.inbox.task.Task;
import com.rnd.inbox.task.TaskExtractionProvenance;
import com.rnd.inbox.task.TaskRoleContext;
import com.rnd.inbox.task.TaskStatus;
import com.rnd.inbox.task.TaskTier;
import com.rnd.inbox.team.TeamRepository;
import com.rnd.inbox.tenant.TenantRepository;
import com.rnd.inbox.user.UserRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
public class StructuredTaskMapper {

    private final TenantRepository tenantRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final Clock clock;

    public StructuredTaskMapper(TenantRepository tenantRepository, ProjectRepository projectRepository,
            UserRepository userRepository, TeamRepository teamRepository, Clock clock) {
        this.tenantRepository = tenantRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.clock = clock;
    }

    public Task map(StructuredTaskEventDto dto) {
        Task task = new Task();
        task.setTenant(tenantRepository.getReferenceById(UUID.fromString(dto.getTenantId())));
        if (dto.getProjectId() != null) {
            task.setProject(projectRepository.getReferenceById(UUID.fromString(dto.getProjectId())));
        }
        task.setTitle(dto.getTitle());
        task.setDescription(dto.getDescription());
        task.setSourceType(SourceType.valueOf(dto.getSourceType()));
        task.setSourceRef(dto.getSourceRef());
        task.setTier(dto.getTier() != null ? TaskTier.valueOf(dto.getTier()) : null);
        task.setRoleContext(dto.getRoleContext() != null ? TaskRoleContext.valueOf(dto.getRoleContext()) : null);
        task.setUrgency(dto.getUrgency() != null ? dto.getUrgency().shortValue() : null);
        task.setEffortEstimateMinutes(dto.getEffortEstimateMinutes());
        task.setDeadline(dto.getDeadline() != null ? Instant.parse(dto.getDeadline()) : null);
        task.setStatus(dto.getStatus() != null ? TaskStatus.valueOf(dto.getStatus()) : TaskStatus.NEW);
        task.setAvailable(true);
        task.setExtractionProvenance(TaskExtractionProvenance.ETL);
        task.setConfidence(dto.getConfidence() != null ? dto.getConfidence().floatValue() : null);
        Instant now = Instant.now(clock);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    public Assignment mapAssignment(StructuredTaskEventDto dto) {
        if (dto.getAssigneeUserId() == null && dto.getAssigneeTeamId() == null) {
            return null;
        }

        Assignment assignment = new Assignment();
        if (dto.getAssigneeUserId() != null) {
            assignment.setUser(userRepository.getReferenceById(UUID.fromString(dto.getAssigneeUserId())));
        }
        if (dto.getAssigneeTeamId() != null) {
            assignment.setTeam(teamRepository.getReferenceById(UUID.fromString(dto.getAssigneeTeamId())));
        }
        assignment.setAssignmentRole(AssignmentRole.valueOf(dto.getAssignmentRole()));
        assignment.setAssignedAt(Instant.now(clock));
        if (dto.getAssignedByUserId() != null) {
            assignment.setAssignedBy(userRepository.getReferenceById(UUID.fromString(dto.getAssignedByUserId())));
        }
        return assignment;
    }
}
