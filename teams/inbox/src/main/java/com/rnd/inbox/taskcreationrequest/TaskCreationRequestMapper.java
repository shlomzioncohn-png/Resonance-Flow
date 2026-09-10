package com.rnd.inbox.taskcreationrequest;

import com.rnd.inbox.project.Project;
import com.rnd.inbox.task.SourceType;
import com.rnd.inbox.task.Task;
import com.rnd.inbox.task.TaskExtractionProvenance;
import com.rnd.inbox.task.TaskRoleContext;
import com.rnd.inbox.task.TaskStatus;
import com.rnd.inbox.task.TaskTier;
import com.rnd.inbox.tenant.Tenant;
import com.rnd.inbox.user.User;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class TaskCreationRequestMapper {

    private final Clock clock;

    public TaskCreationRequestMapper(Clock clock) {
        this.clock = clock;
    }

    public Task map(TaskCreationRequestEventDto dto, Tenant tenant, Project project, User createdBy) {
        Task task = new Task();
        task.setTenant(tenant);
        task.setProject(project);
        task.setCreatedBy(createdBy);
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
