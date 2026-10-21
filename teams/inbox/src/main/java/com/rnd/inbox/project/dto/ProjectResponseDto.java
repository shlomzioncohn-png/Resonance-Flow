package com.rnd.inbox.project.dto;

import com.rnd.inbox.project.ProjectStatus;
import com.rnd.inbox.tenant.dto.TenantSummaryDto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class ProjectResponseDto {

    private final UUID id;
    private final TenantSummaryDto tenant;
    private final String name;
    private final String description;
    private final UUID ownerId;
    private final String ownerDisplayName;
    private final UUID teamId;
    private final String teamName;
    private final ProjectStatus status;
    private final LocalDate startDate;
    private final LocalDate targetDate;
    private final Instant createdAt;
    private final Instant updatedAt;

    public ProjectResponseDto(UUID id, TenantSummaryDto tenant, String name, String description,
                              UUID ownerId, String ownerDisplayName, UUID teamId, String teamName,
                              ProjectStatus status, LocalDate startDate, LocalDate targetDate,
                              Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.tenant = tenant;
        this.name = name;
        this.description = description;
        this.ownerId = ownerId;
        this.ownerDisplayName = ownerDisplayName;
        this.teamId = teamId;
        this.teamName = teamName;
        this.status = status;
        this.startDate = startDate;
        this.targetDate = targetDate;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public TenantSummaryDto getTenant() {
        return tenant;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getOwnerDisplayName() {
        return ownerDisplayName;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public String getTeamName() {
        return teamName;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
