package com.rnd.inbox.taskcreationrequest;

import com.rnd.inbox.shared.messaging.validation.IsoInstant;
import com.rnd.inbox.shared.messaging.validation.ValidEnum;
import com.rnd.inbox.shared.messaging.validation.ValidUuid;
import com.rnd.inbox.task.AssigneeDto;
import com.rnd.inbox.task.SourceType;
import com.rnd.inbox.task.TaskRoleContext;
import com.rnd.inbox.task.TaskStatus;
import com.rnd.inbox.task.TaskTier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public class TaskCreationRequestEventDto {

    @ValidUuid
    private String eventId;

    @NotBlank
    @ValidUuid
    private String correlationId;

    @NotBlank
    @ValidUuid
    private String tenantId;

    @ValidUuid
    private String projectId;

    @NotBlank
    private String title;

    private String description;

    @NotBlank
    @ValidEnum(enumClass = SourceType.class)
    private String sourceType;

    @NotBlank
    private String sourceRef;

    @ValidEnum(enumClass = TaskTier.class)
    private String tier;

    @ValidEnum(enumClass = TaskRoleContext.class)
    private String roleContext;

    @Min(1)
    @Max(5)
    private Integer urgency;

    @Positive
    private Integer effortEstimateMinutes;

    @IsoInstant
    private String deadline;

    @ValidEnum(enumClass = TaskStatus.class)
    private String status;

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double confidence;

    @NotNull
    private Boolean hasDependencies;

    @ValidUuid
    private String createdByUserId;

    @Valid
    private List<AssigneeDto> assignees;

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public String getRoleContext() {
        return roleContext;
    }

    public void setRoleContext(String roleContext) {
        this.roleContext = roleContext;
    }

    public Integer getUrgency() {
        return urgency;
    }

    public void setUrgency(Integer urgency) {
        this.urgency = urgency;
    }

    public Integer getEffortEstimateMinutes() {
        return effortEstimateMinutes;
    }

    public void setEffortEstimateMinutes(Integer effortEstimateMinutes) {
        this.effortEstimateMinutes = effortEstimateMinutes;
    }

    public String getDeadline() {
        return deadline;
    }

    public void setDeadline(String deadline) {
        this.deadline = deadline;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public Boolean getHasDependencies() {
        return hasDependencies;
    }

    public void setHasDependencies(Boolean hasDependencies) {
        this.hasDependencies = hasDependencies;
    }

    public String getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(String createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public List<AssigneeDto> getAssignees() {
        return assignees;
    }

    public void setAssignees(List<AssigneeDto> assignees) {
        this.assignees = assignees;
    }
}
