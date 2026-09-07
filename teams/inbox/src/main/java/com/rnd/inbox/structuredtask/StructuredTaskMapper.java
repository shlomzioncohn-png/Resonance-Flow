package com.rnd.inbox.structuredtask;

import com.rnd.inbox.task.Assignment;
import com.rnd.inbox.task.AssignmentRole;
import com.rnd.inbox.task.SourceType;
import com.rnd.inbox.task.Task;
import com.rnd.inbox.task.TaskExtractionProvenance;
import com.rnd.inbox.task.TaskRoleContext;
import com.rnd.inbox.task.TaskStatus;
import com.rnd.inbox.task.TaskTier;
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
        Instant now = Instant.now(clock);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    public Assignment mapAssignment(StructuredTaskEventDto dto, StructuredTaskReferences references) {
        if (references.assignee() == null && references.assigneeTeam() == null) {
            return null;
        }

        Assignment assignment = new Assignment();
        assignment.setUser(references.assignee());
        assignment.setTeam(references.assigneeTeam());
        assignment.setAssignmentRole(AssignmentRole.valueOf(dto.getAssignmentRole()));
        assignment.setAssignedAt(Instant.now(clock));
        assignment.setAssignedBy(references.assignedBy());
        return assignment;
    }
}
