package com.rnd.inbox.task;


import java.time.Instant;
import java.util.UUID;

public final class TaskUpdatedPayload {

    private final UUID taskId;
    private final UUID tenantId;
    private final UUID projectId;
    private final String title;
    private final String description;
    private final boolean isAvailable;
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
    private final Float effortEstimateConfidence;
    private final UUID suggestedOwnerUserId;
    private final Float suggestedOwnerConfidence;
    private final Instant updatedAt;

    private TaskUpdatedPayload(UUID taskId, UUID tenantId, UUID projectId, String title, String description,
                                boolean isAvailable, SourceType sourceType, String sourceRef, TaskTier tier,
                                TaskRoleContext roleContext, Short urgency, Integer effortEstimateMinutes,
                                Instant deadline, TaskStatus status, TaskExtractionProvenance extractionProvenance,
                                Float confidence, Float effortEstimateConfidence, UUID suggestedOwnerUserId,
                                Float suggestedOwnerConfidence, Instant updatedAt) {
        this.taskId = taskId;
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.title = title;
        this.description = description;
        this.isAvailable = isAvailable;
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
        this.effortEstimateConfidence = effortEstimateConfidence;
        this.suggestedOwnerUserId = suggestedOwnerUserId;
        this.suggestedOwnerConfidence = suggestedOwnerConfidence;
        this.updatedAt = updatedAt;
    }

    public static TaskUpdatedPayload from(Task task) {
        return new TaskUpdatedPayload(
                task.getId(),
                task.getTenant() != null ? task.getTenant().getId() : null,
                task.getProject() != null ? task.getProject().getId() : null,
                task.getTitle(),
                task.getDescription(),
                task.isAvailable(),
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
                task.getEffortEstimateConfidence(),
                task.getSuggestedOwnerUser() != null ? task.getSuggestedOwnerUser().getId() : null,
                task.getSuggestedOwnerConfidence(),
                task.getUpdatedAt()
        );
    }

    public UUID getTaskId() {
        return taskId;
    }

    public UUID getTenantId() {
        return tenantId;
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

    public boolean isAvailable() {
        return isAvailable;
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

    public Float getEffortEstimateConfidence() {
        return effortEstimateConfidence;
    }

    public UUID getSuggestedOwnerUserId() {
        return suggestedOwnerUserId;
    }

    public Float getSuggestedOwnerConfidence() {
        return suggestedOwnerConfidence;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
