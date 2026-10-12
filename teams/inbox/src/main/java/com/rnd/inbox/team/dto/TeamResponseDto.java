package com.rnd.inbox.team.dto;
import com.rnd.inbox.team.model.TeamStatus;
import com.rnd.inbox.tenant.dto.TenantSummaryDto;

import java.time.Instant;
import java.util.UUID;

public final class TeamResponseDto {

    private final UUID id;
    private final TenantSummaryDto tenant;
    private final String name;
    private final UUID teamLeadId;
    private final TeamStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    public TeamResponseDto(UUID id, TenantSummaryDto tenant, String name, UUID teamLeadId, TeamStatus status,
                            Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.tenant = tenant;
        this.name = name;
        this.teamLeadId = teamLeadId;
        this.status = status;
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

    public UUID getTeamLeadId() {
        return teamLeadId;
    }

    public TeamStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
