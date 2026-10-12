package com.rnd.inbox.structuredtask;

import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.model.TaskExtractionProvenance;
import com.rnd.inbox.task.security.TaskRoleContext;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.model.TaskTier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class StructuredTaskMapper {

    private final Clock clock;

    public StructuredTaskMapper(Clock clock) {
        this.clock = clock;
    }

    public Task map(StructuredTaskEventDto dto, StructuredTaskReferences references) {
        Task task = new Task();
        task.setTenant(references.tenant());
        task.setProject(references.project());
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
        task.setCreatedBy(references.createdBy());
        Instant now = Instant.now(clock);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }
}
