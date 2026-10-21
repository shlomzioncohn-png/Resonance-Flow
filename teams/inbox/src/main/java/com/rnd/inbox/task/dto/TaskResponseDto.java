package com.rnd.inbox.task.dto;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.rnd.inbox.shared.json.DisplayDateFormat;
import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.TaskExtractionProvenance;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.model.TaskTier;
import com.rnd.inbox.task.security.TaskRoleContext;
import com.rnd.inbox.tenant.dto.TenantSummaryDto;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TaskResponseDto {

    private final UUID id;
    private final TenantSummaryDto tenant;
    private final UUID projectId;
    private final String projectName;
    private final String title;
    private final String description;
    private final SourceType sourceType;
    private final String sourceRef;
    private final TaskTier tier;
    private final TaskRoleContext roleContext;
    private final Short urgency;
    private final Integer effortEstimateMinutes;
    @JsonSerialize(using = DisplayDateFormat.DateOnlySerializer.class)
    @Schema(type = "string", example = "12/10/2026")
    private final Instant deadline;
    private final TaskStatus status;
    private final boolean available;
    private final Float priorityScore;
    @JsonSerialize(using = DisplayDateFormat.DateTimeSerializer.class)
    @Schema(type = "string", example = "12/10/2026 09:00")
    private final Instant snoozedUntil;
    private final TaskExtractionProvenance extractionProvenance;
    private final Float confidence;
    private final UUID ownerId;
    private final String ownerDisplayName;
    private final Float effortEstimateConfidence;
    private final UUID suggestedOwnerUserId;
    private final Float suggestedOwnerConfidence;
    private final UUID createdById;
    @JsonSerialize(using = DisplayDateFormat.DateTimeSerializer.class)
    @Schema(type = "string", example = "01/10/2026 08:12")
    private final Instant createdAt;
    @JsonSerialize(using = DisplayDateFormat.DateTimeSerializer.class)
    @Schema(type = "string", example = "05/10/2026 13:30")
    private final Instant updatedAt;
    @JsonSerialize(using = DisplayDateFormat.DateTimeSerializer.class)
    @Schema(type = "string", example = "12/10/2026 14:30")
    private final Instant completedAt;
    private final Map<String, Object> metadata;
    private final List<AssignmentResponseDto> assignees;

    public TaskResponseDto(UUID id, TenantSummaryDto tenant, UUID projectId, String projectName, String title,
                            String description, SourceType sourceType, String sourceRef, TaskTier tier,
                            TaskRoleContext roleContext, Short urgency, Integer effortEstimateMinutes,
                            Instant deadline, TaskStatus status, boolean available, Float priorityScore,
                            Instant snoozedUntil, TaskExtractionProvenance extractionProvenance, Float confidence,
                            UUID ownerId, String ownerDisplayName, Float effortEstimateConfidence,
                            UUID suggestedOwnerUserId, Float suggestedOwnerConfidence, UUID createdById,
                            Instant createdAt, Instant updatedAt, Instant completedAt, Map<String, Object> metadata,
                            List<AssignmentResponseDto> assignees) {
        this.id = id;
        this.tenant = tenant;
        this.projectId = projectId;
        this.projectName = projectName;
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
        this.available = available;
        this.priorityScore = priorityScore;
        this.snoozedUntil = snoozedUntil;
        this.extractionProvenance = extractionProvenance;
        this.confidence = confidence;
        this.ownerId = ownerId;
        this.ownerDisplayName = ownerDisplayName;
        this.effortEstimateConfidence = effortEstimateConfidence;
        this.suggestedOwnerUserId = suggestedOwnerUserId;
        this.suggestedOwnerConfidence = suggestedOwnerConfidence;
        this.createdById = createdById;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.completedAt = completedAt;
        this.metadata = metadata;
        this.assignees = assignees;
    }

    public UUID getId() {
        return id;
    }

    public TenantSummaryDto getTenant() {
        return tenant;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getProjectName() {
        return projectName;
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

    public boolean isAvailable() {
        return available;
    }

    public Float getPriorityScore() {
        return priorityScore;
    }

    public Instant getSnoozedUntil() {
        return snoozedUntil;
    }

    public TaskExtractionProvenance getExtractionProvenance() {
        return extractionProvenance;
    }

    public Float getConfidence() {
        return confidence;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getOwnerDisplayName() {
        return ownerDisplayName;
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

    public UUID getCreatedById() {
        return createdById;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public List<AssignmentResponseDto> getAssignees() {
        return assignees;
    }
}
