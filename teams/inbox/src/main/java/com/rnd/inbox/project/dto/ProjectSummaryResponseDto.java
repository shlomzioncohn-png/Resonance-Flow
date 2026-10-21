package com.rnd.inbox.project.dto;

import com.rnd.inbox.project.ProjectStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class ProjectSummaryResponseDto {

    private final UUID projectId;
    private final String name;
    private final ProjectStatus status;
    private final UUID teamId;
    private final String teamName;
    private final UUID ownerId;
    private final String ownerDisplayName;
    private final LocalDate targetDate;
    private final ProjectTaskCountsDto taskCounts;
    private final int progressPercent;
    private final Instant lastActivityAt;

    public ProjectSummaryResponseDto(UUID projectId, String name, ProjectStatus status, UUID teamId,
                                     String teamName, UUID ownerId, String ownerDisplayName,
                                     LocalDate targetDate, ProjectTaskCountsDto taskCounts,
                                     int progressPercent, Instant lastActivityAt) {
        this.projectId = projectId;
        this.name = name;
        this.status = status;
        this.teamId = teamId;
        this.teamName = teamName;
        this.ownerId = ownerId;
        this.ownerDisplayName = ownerDisplayName;
        this.targetDate = targetDate;
        this.taskCounts = taskCounts;
        this.progressPercent = progressPercent;
        this.lastActivityAt = lastActivityAt;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public String getTeamName() {
        return teamName;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getOwnerDisplayName() {
        return ownerDisplayName;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public ProjectTaskCountsDto getTaskCounts() {
        return taskCounts;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }
}
