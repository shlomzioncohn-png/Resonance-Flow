package com.rnd.inbox.task.dto;
import com.rnd.inbox.shared.messaging.validation.ValidUuid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public class TaskCreateRequestDto {

    @NotBlank
    @ValidUuid
    private String tenantId;

    @NotBlank
    private String title;

    private String description;

    @ValidUuid
    private String projectId;

    @ValidUuid
    private String ownerId;

    @Min(1)
    @Max(5)
    private Short urgency;

    @Min(1)
    private Integer effortEstimateMinutes;

    private Instant deadline;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
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

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public Short getUrgency() {
        return urgency;
    }

    public void setUrgency(Short urgency) {
        this.urgency = urgency;
    }

    public Integer getEffortEstimateMinutes() {
        return effortEstimateMinutes;
    }

    public void setEffortEstimateMinutes(Integer effortEstimateMinutes) {
        this.effortEstimateMinutes = effortEstimateMinutes;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public void setDeadline(Instant deadline) {
        this.deadline = deadline;
    }
}
