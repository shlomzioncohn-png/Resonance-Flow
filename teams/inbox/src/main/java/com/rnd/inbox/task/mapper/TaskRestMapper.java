package com.rnd.inbox.task.mapper;
import com.rnd.inbox.task.assignment.Assignment;
import com.rnd.inbox.task.assignment.AssignmentMapper;
import com.rnd.inbox.task.controller.TaskController;
import com.rnd.inbox.task.dto.TaskCreateRequestDto;
import com.rnd.inbox.task.dto.TaskResponseDto;
import com.rnd.inbox.task.dto.TaskUpdateRequestDto;
import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.model.TaskExtractionProvenance;
import com.rnd.inbox.task.model.TaskFeedback;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.project.Project;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.tenant.dto.TenantSummaryDto;
import com.rnd.inbox.user.model.User;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Maps between the REST-facing DTOs and {@link Task}, for the endpoints in {@link TaskController}.
 * Distinct from {@link TaskMapper}, which already exists for the Kafka ingestion path
 * (entity-to-entity {@code applyUpdate}) - a second class was needed because that name was taken
 * by a mapper with a genuinely different shape and purpose, not because this one duplicates it.
 */
@Component
public class TaskRestMapper {

    private final Clock clock;
    private final AssignmentMapper assignmentMapper;

    public TaskRestMapper(Clock clock, AssignmentMapper assignmentMapper) {
        this.clock = clock;
        this.assignmentMapper = assignmentMapper;
    }

    public Task toEntity(TaskCreateRequestDto dto, Tenant tenant, Project project, User owner, User createdBy) {
        Task task = new Task();
        task.setTenant(tenant);
        task.setProject(project);
        task.setTitle(dto.getTitle());
        task.setDescription(dto.getDescription());
        task.setOwner(owner);
        task.setCreatedBy(createdBy);
        task.setUrgency(dto.getUrgency());
        task.setEffortEstimateMinutes(dto.getEffortEstimateMinutes());
        task.setDeadline(dto.getDeadline());
        task.setSourceType(SourceType.MANUAL);
        task.setExtractionProvenance(TaskExtractionProvenance.MANUAL);
        task.setStatus(TaskStatus.NEW);
        task.setAvailable(false);
        Instant now = Instant.now(clock);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    // project/owner are already-resolved (or null, meaning "clear the relation") - the Service
    // resolves them via their own repositories only when the corresponding JsonNullable field is
    // actually present, since that resolution is a repository concern the Mapper never does.
    public void applyUpdate(Task task, TaskUpdateRequestDto dto, Project resolvedProject, User resolvedOwner) {
        if (dto.getTitle() != null) {
            task.setTitle(dto.getTitle());
        }
        if (dto.getDescription().isPresent()) {
            task.setDescription(dto.getDescription().get());
        }
        if (dto.getProjectId().isPresent()) {
            task.setProject(resolvedProject);
        }
        if (dto.getOwnerId().isPresent()) {
            task.setOwner(resolvedOwner);
        }
        if (dto.getUrgency().isPresent()) {
            task.setUrgency(dto.getUrgency().get());
        }
        if (dto.getEffortEstimateMinutes().isPresent()) {
            task.setEffortEstimateMinutes(dto.getEffortEstimateMinutes().get());
        }
        if (dto.getDeadline().isPresent()) {
            task.setDeadline(dto.getDeadline().get());
        }
        task.setUpdatedAt(Instant.now(clock));
    }

    public void applyStatusChange(Task task, TaskStatus newStatus, Instant snoozedUntil) {
        task.setStatus(newStatus);
        task.setSnoozedUntil(snoozedUntil);
        if (newStatus == TaskStatus.DONE) {
            task.setCompletedAt(Instant.now(clock));
        }
        task.setUpdatedAt(Instant.now(clock));
    }

    public TaskFeedback toFeedbackEntity(Task task, User user, String feedbackText) {
        TaskFeedback feedback = new TaskFeedback();
        feedback.setTask(task);
        feedback.setUser(user);
        feedback.setFeedbackText(feedbackText);
        feedback.setCreatedAt(Instant.now(clock));
        return feedback;
    }

    public TaskResponseDto toResponseDto(Task task, List<Assignment> assignments) {
        Tenant tenant = task.getTenant();
        TenantSummaryDto tenantSummary = tenant != null
                ? new TenantSummaryDto(tenant.getId(), tenant.getName(), tenant.getSlug())
                : null;

        return new TaskResponseDto(
                task.getId(),
                tenantSummary,
                task.getProject() != null ? task.getProject().getId() : null,
                task.getProject() != null ? task.getProject().getName() : null,
                task.getTitle(),
                task.getDescription(),
                task.getSourceType(),
                task.getSourceRef(),
                task.getTier(),
                task.getRoleContext(),
                task.getUrgency(),
                task.getEffortEstimateMinutes(),
                task.getDeadline(),
                task.getStatus(),
                task.isAvailable(),
                task.getPriorityScore(),
                task.getSnoozedUntil(),
                task.getExtractionProvenance(),
                task.getConfidence(),
                task.getOwner() != null ? task.getOwner().getId() : null,
                task.getOwner() != null ? task.getOwner().getDisplayName() : null,
                task.getEffortEstimateConfidence(),
                task.getSuggestedOwnerUser() != null ? task.getSuggestedOwnerUser().getId() : null,
                task.getSuggestedOwnerConfidence(),
                task.getCreatedBy() != null ? task.getCreatedBy().getId() : null,
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getCompletedAt(),
                task.getMetadata(),
                assignments.stream().map(assignmentMapper::toResponseDto).toList());
    }
}
