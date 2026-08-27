package com.rnd.inbox.dto;

import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.model.enums.SourceType;
import com.rnd.inbox.model.enums.TaskExtractionProvenance;
import com.rnd.inbox.model.enums.TaskRoleContext;
import com.rnd.inbox.model.enums.TaskStatus;
import com.rnd.inbox.model.enums.TaskTier;

import java.time.Instant;
import java.util.UUID;

public final class TaskCreatedPayload {

    private final UUID taskId;
    private final UUID organizationId;
    private final UUID projectId;
    private final String title;
    private final String description;
    private final SourceType sourceType;
    private final String sourceRef;
    private final TaskTier tier;
    private final TaskRoleContext roleContext;
    private final Short urgency;
    private final Integer effortEstimateMinutes;
    private final Instant deadline;
    private final TaskStatus status;
    private final TaskExtractionProvenance extractionProvenance;
    private final Float confidence;
    private final UUID createdBy;
    private final Instant createdAt;

    private TaskCreatedPayload(UUID taskId, UUID organizationId, UUID projectId, String title, String description,
                                SourceType sourceType, String sourceRef, TaskTier tier, TaskRoleContext roleContext,
                                Short urgency, Integer effortEstimateMinutes, Instant deadline, TaskStatus status,
                                TaskExtractionProvenance extractionProvenance, Float confidence, UUID createdBy,
                                Instant createdAt) {
        this.taskId = taskId;
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.title = title;
        this.description = description;
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
        this.tier = tier;
        this.roleContext = roleContext;
        this.urgency = urgency;
        this.effortEstimateMinutes = effortEstimateMinutes;
        this.deadline = deadline;
        this.status = status;
        this.extractionProvenance = extractionProvenance;
        this.confidence = confidence;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public static TaskCreatedPayload from(Task task) {
        return new TaskCreatedPayload(
                task.getId(),
                task.getOrganization() != null ? task.getOrganization().getId() : null,
                task.getProject() != null ? task.getProject().getId() : null,
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
                task.getExtractionProvenance(),
                task.getConfidence(),
                task.getCreatedBy() != null ? task.getCreatedBy().getId() : null,
                task.getCreatedAt()
        );
    }

    public UUID getTaskId() {
        return taskId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public TaskTier getTier() {
        return tier;
    }

    public TaskRoleContext getRoleContext() {
        return roleContext;
    }

    public Short getUrgency() {
        return urgency;
    }

    public Integer getEffortEstimateMinutes() {
        return effortEstimateMinutes;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public TaskExtractionProvenance getExtractionProvenance() {
        return extractionProvenance;
    }

    public Float getConfidence() {
        return confidence;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
