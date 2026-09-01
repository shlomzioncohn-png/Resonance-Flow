package com.rnd.inbox.bridge.task;

import com.rnd.inbox.messaging.validation.IsoInstant;
import com.rnd.inbox.messaging.validation.ValidEnum;
import com.rnd.inbox.messaging.validation.ValidUuid;
import com.rnd.inbox.model.enums.AssignmentRole;
import com.rnd.inbox.model.enums.SourceType;
import com.rnd.inbox.model.enums.TaskRoleContext;
import com.rnd.inbox.model.enums.TaskStatus;
import com.rnd.inbox.model.enums.TaskTier;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

@ConsistentAssignment
public class BridgeTaskEventDto {

    @ValidUuid
    private String eventId;

    @NotBlank
    @ValidUuid
    private String organizationId;

    @ValidUuid
    private String projectId;

    @NotBlank
    private String title;

    private String description;

    @NotBlank
    @ValidEnum(enumClass = SourceType.class, allowed = {"GITHUB_ISSUE", "GITHUB_PR", "CRM_DEAL"})
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

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double confidence;

    @ValidUuid
    private String assigneeUserId;

    @ValidUuid
    private String assigneeTeamId;

    @ValidEnum(enumClass = AssignmentRole.class)
    private String assignmentRole;

    @ValidUuid
    private String assignedByUserId;

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
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

    public String getAssigneeUserId() {
        return assigneeUserId;
    }

    public void setAssigneeUserId(String assigneeUserId) {
        this.assigneeUserId = assigneeUserId;
    }

    public String getAssigneeTeamId() {
        return assigneeTeamId;
    }

    public void setAssigneeTeamId(String assigneeTeamId) {
        this.assigneeTeamId = assigneeTeamId;
    }

    public String getAssignmentRole() {
        return assignmentRole;
    }

    public void setAssignmentRole(String assignmentRole) {
        this.assignmentRole = assignmentRole;
    }

    public String getAssignedByUserId() {
        return assignedByUserId;
    }

    public void setAssignedByUserId(String assignedByUserId) {
        this.assignedByUserId = assignedByUserId;
    }
}
