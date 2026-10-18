package com.rnd.inbox.project.dto;

import com.rnd.inbox.project.ProjectStatus;

import java.util.UUID;

public class ProjectFilterDto {

    private String search;
    private ProjectStatus status;
    private UUID teamId;
    private UUID ownerId;

    public String getSearch() {
        return search;
    }

    public void setSearch(String search) {
        this.search = search;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public void setStatus(ProjectStatus status) {
        this.status = status;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public void setTeamId(UUID teamId) {
        this.teamId = teamId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }
}
