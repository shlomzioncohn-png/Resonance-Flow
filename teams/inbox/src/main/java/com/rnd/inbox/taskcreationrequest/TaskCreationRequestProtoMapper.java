package com.rnd.inbox.taskcreationrequest;

import com.google.protobuf.util.Timestamps;
import com.rnd.inbox.event.Assignee;
import com.rnd.inbox.event.AssigneeType;
import com.rnd.inbox.event.AssignmentRole;
import com.rnd.inbox.event.SourceType;
import com.rnd.inbox.event.TaskCreationRequestedEvent;
import com.rnd.inbox.event.TaskRoleContext;
import com.rnd.inbox.event.TaskStatus;
import com.rnd.inbox.event.TaskTier;
import com.rnd.inbox.task.assignment.AssigneeDto;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class TaskCreationRequestProtoMapper {

    public TaskCreationRequestEventDto toDto(TaskCreationRequestedEvent proto) {
        TaskCreationRequestEventDto dto = new TaskCreationRequestEventDto();
        dto.setEventId(proto.hasEventId() ? proto.getEventId() : null);
        dto.setCorrelationId(proto.getCorrelationId());
        dto.setTenantId(proto.getTenantId());
        dto.setProjectId(proto.hasProjectId() ? proto.getProjectId() : null);
        dto.setTitle(proto.getTitle());
        dto.setDescription(proto.hasDescription() ? proto.getDescription() : null);
        dto.setSourceType(fromSourceType(proto.getSourceType()));
        dto.setSourceRef(proto.getSourceRef());
        dto.setTier(proto.hasTier() ? fromTaskTier(proto.getTier()) : null);
        dto.setRoleContext(proto.hasRoleContext() ? fromTaskRoleContext(proto.getRoleContext()) : null);
        dto.setUrgency(proto.hasUrgency() ? proto.getUrgency() : null);
        dto.setEffortEstimateMinutes(proto.hasEffortEstimateMinutes() ? proto.getEffortEstimateMinutes() : null);
        dto.setDeadline(proto.hasDeadline() ? toIsoInstant(proto.getDeadline()) : null);
        dto.setStatus(proto.hasStatus() ? fromTaskStatus(proto.getStatus()) : null);
        dto.setConfidence(proto.hasConfidence() ? proto.getConfidence() : null);
        dto.setHasDependencies(proto.hasHasDependencies() ? proto.getHasDependencies() : null);
        dto.setCreatedByUserId(proto.hasCreatedByUserId() ? proto.getCreatedByUserId() : null);
        dto.setAssignees(fromAssignees(proto.getAssigneesList()));
        dto.setEffortEstimateConfidence(proto.hasEffortEstimateConfidence() ? proto.getEffortEstimateConfidence() : null);
        dto.setSuggestedOwnerUserId(proto.hasSuggestedOwnerUserId() ? proto.getSuggestedOwnerUserId() : null);
        dto.setSuggestedOwnerConfidence(proto.hasSuggestedOwnerConfidence() ? proto.getSuggestedOwnerConfidence() : null);
        return dto;
    }

    private List<AssigneeDto> fromAssignees(List<Assignee> assignees) {
        return assignees.stream().map(this::fromAssignee).toList();
    }

    private AssigneeDto fromAssignee(Assignee assignee) {
        AssigneeDto dto = new AssigneeDto();
        dto.setAssigneeType(fromAssigneeType(assignee.getAssigneeType()));
        dto.setId(assignee.getId());
        dto.setAssignmentRole(fromAssignmentRole(assignee.getAssignmentRole()));
        dto.setAssignedBy(assignee.hasAssignedBy() ? assignee.getAssignedBy() : null);
        return dto;
    }

    private String fromAssigneeType(AssigneeType assigneeType) {
        return assigneeType.name().substring("ASSIGNEE_TYPE_".length());
    }

    private String fromAssignmentRole(AssignmentRole assignmentRole) {
        return assignmentRole.name().substring("ASSIGNMENT_ROLE_".length());
    }

    private String fromSourceType(SourceType sourceType) {
        return sourceType.name().substring("SOURCE_TYPE_".length());
    }

    private String fromTaskTier(TaskTier tier) {
        return tier.name().substring("TASK_TIER_".length());
    }

    private String fromTaskRoleContext(TaskRoleContext roleContext) {
        return roleContext.name().substring("TASK_ROLE_CONTEXT_".length());
    }

    private String fromTaskStatus(TaskStatus status) {
        return status.name().substring("TASK_STATUS_".length());
    }

    private String toIsoInstant(com.google.protobuf.Timestamp timestamp) {
        return Instant.ofEpochMilli(Timestamps.toMillis(timestamp)).toString();
    }
}
