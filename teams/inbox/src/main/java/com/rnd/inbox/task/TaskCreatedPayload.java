package com.rnd.inbox.task;


import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class TaskCreatedPayload {

    private final UUID taskId;
    private final UUID tenantId;
    private final UUID projectId;
    private final String title;

    private final String description;
    private final SourceType sourceType;
    private final String sourceRef;
    private final boolean isAvailable;
    private final TaskTier tier;
    private final TaskRoleContext roleContext;
    private final Short urgency;
    private final Integer effortEstimateMinutes;
    private final Instant deadline;
    private final TaskStatus status;
    private final TaskExtractionProvenance extractionProvenance;
    private final Float confidence;
    private final Float effortEstimateConfidence;
    private final UUID createdBy;
    private final Instant createdAt;
    private final Map<String, Object> metadata;

    private TaskCreatedPayload(UUID taskId, UUID tenantId, UUID projectId, String title, String description,boolean isAvailable,
                                SourceType sourceType, String sourceRef, TaskTier tier, TaskRoleContext roleContext,
                                Short urgency, Integer effortEstimateMinutes, Instant deadline, TaskStatus status,
                                TaskExtractionProvenance extractionProvenance, Float confidence,
                                Float effortEstimateConfidence,
                                UUID createdBy, Instant createdAt, Map<String, Object> metadata) {
        this.taskId = taskId;
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.title = title;
        this.isAvailable=isAvailable;
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
        this.effortEstimateConfidence = effortEstimateConfidence;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.metadata = metadata;
    }

    public static TaskCreatedPayload from(Task task) {
        return new TaskCreatedPayload(
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
                task.getCreatedBy() != null ? task.getCreatedBy().getId() : null,
                task.getCreatedAt(),
                task.getMetadata()
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

    public SourceType getSourceType() {
        return sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public boolean isAvailable() {
        return isAvailable;
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

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
