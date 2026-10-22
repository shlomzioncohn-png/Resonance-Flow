package com.rnd.inbox.project.dto;

import com.rnd.inbox.project.ProjectStatus;
import com.rnd.inbox.shared.messaging.validation.ValidEnum;
import com.rnd.inbox.shared.messaging.validation.ValidUuid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public class ProjectCreateRequestDto {

    @NotBlank
    @ValidUuid
    private String tenantId;

    @NotBlank
    @Size(max = 255)
    private String name;

    private String description;

    @ValidUuid
    private String ownerId;

    @ValidUuid
    private String teamId;

    // Optional - a project created without a status starts as PLANNED (ProjectMapper.toEntity).
    @ValidEnum(enumClass = ProjectStatus.class)
    private String status;

    private LocalDate startDate;

    private LocalDate targetDate;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getTeamId() {
        return teamId;
    }

    public void setTeamId(String teamId) {
        this.teamId = teamId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public void setTargetDate(LocalDate targetDate) {
        this.targetDate = targetDate;
    }
}
