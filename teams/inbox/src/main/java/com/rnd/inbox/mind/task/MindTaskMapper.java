package com.rnd.inbox.mind.task;

import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.model.enums.SourceType;
import com.rnd.inbox.model.enums.TaskExtractionProvenance;
import com.rnd.inbox.model.enums.TaskRoleContext;
import com.rnd.inbox.model.enums.TaskStatus;
import com.rnd.inbox.model.enums.TaskTier;
import com.rnd.inbox.repository.OrganizationRepository;
import com.rnd.inbox.repository.ProjectRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
public class MindTaskMapper {

    private final OrganizationRepository organizationRepository;
    private final ProjectRepository projectRepository;
    private final Clock clock;

    public MindTaskMapper(OrganizationRepository organizationRepository, ProjectRepository projectRepository,
            Clock clock) {
        this.organizationRepository = organizationRepository;
        this.projectRepository = projectRepository;
        this.clock = clock;
    }

    public Task map(MindTaskEventDto dto) {
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
        task.setAvailable(!dto.getHasDependencies());
        task.setExtractionProvenance(TaskExtractionProvenance.ML_INFERRED);
        task.setConfidence(dto.getConfidence().floatValue());
        Instant now = Instant.now(clock);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }
}
