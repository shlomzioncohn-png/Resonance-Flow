package com.rnd.inbox.bridge.task;

import com.rnd.inbox.model.entity.Assignment;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.model.enums.AssignmentRole;
import com.rnd.inbox.model.enums.SourceType;
import com.rnd.inbox.model.enums.TaskExtractionProvenance;
import com.rnd.inbox.model.enums.TaskRoleContext;
import com.rnd.inbox.model.enums.TaskStatus;
import com.rnd.inbox.model.enums.TaskTier;
import com.rnd.inbox.repository.OrganizationRepository;
import com.rnd.inbox.repository.ProjectRepository;
import com.rnd.inbox.repository.TeamRepository;
import com.rnd.inbox.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
public class BridgeTaskMapper {

    private final OrganizationRepository organizationRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final Clock clock;

    public BridgeTaskMapper(OrganizationRepository organizationRepository, ProjectRepository projectRepository,
            UserRepository userRepository, TeamRepository teamRepository, Clock clock) {
        this.organizationRepository = organizationRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.clock = clock;
    }

    public Task map(BridgeTaskEventDto dto) {
        Task task = new Task();
        task.setOrganization(organizationRepository.getReferenceById(UUID.fromString(dto.getOrganizationId())));
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

    public Assignment mapAssignment(BridgeTaskEventDto dto) {
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
