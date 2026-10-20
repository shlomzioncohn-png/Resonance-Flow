package com.rnd.inbox.project.mapper;

import com.rnd.inbox.project.Project;
import com.rnd.inbox.project.ProjectStatus;
import com.rnd.inbox.project.dto.ProjectCreateRequestDto;
import com.rnd.inbox.project.dto.ProjectResponseDto;
import com.rnd.inbox.project.dto.ProjectSummaryResponseDto;
import com.rnd.inbox.project.dto.ProjectTaskCountsDto;
import com.rnd.inbox.project.dto.ProjectUpdateRequestDto;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.tenant.dto.TenantSummaryDto;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.model.User;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class ProjectMapper {

    private final Clock clock;

    public ProjectMapper(Clock clock) {
        this.clock = clock;
    }

    public Project toEntity(ProjectCreateRequestDto dto, Tenant tenant, User owner, Team team) {
        Project project = new Project();
        project.setTenant(tenant);
        project.setName(dto.getName());
        project.setDescription(dto.getDescription());
        project.setOwner(owner);
        project.setTeam(team);
        project.setStatus(dto.getStatus() != null ? ProjectStatus.valueOf(dto.getStatus()) : ProjectStatus.PLANNED);
        project.setStartDate(dto.getStartDate());
        project.setTargetDate(dto.getTargetDate());
        Instant now = Instant.now(clock);
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        return project;
    }

    public void applyUpdate(Project project, ProjectUpdateRequestDto dto, User resolvedOwner, Team resolvedTeam) {
        if (dto.getName() != null) {
            project.setName(dto.getName());
        }
        if (dto.getDescription().isPresent()) {
            project.setDescription(dto.getDescription().get());
        }
        if (dto.getOwnerId().isPresent()) {
            project.setOwner(resolvedOwner);
        }
        if (dto.getTeamId().isPresent()) {
            project.setTeam(resolvedTeam);
        }
        if (dto.getStatus() != null) {
            project.setStatus(ProjectStatus.valueOf(dto.getStatus()));
        }
        if (dto.getStartDate().isPresent()) {
            project.setStartDate(dto.getStartDate().get());
        }
        if (dto.getTargetDate().isPresent()) {
            project.setTargetDate(dto.getTargetDate().get());
        }
        project.setUpdatedAt(Instant.now(clock));
    }

    public ProjectResponseDto toResponseDto(Project project) {
        Tenant tenant = project.getTenant();
        TenantSummaryDto tenantSummary = tenant != null
                ? new TenantSummaryDto(tenant.getId(), tenant.getName(), tenant.getSlug())
                : null;
        User owner = project.getOwner();
        Team team = project.getTeam();

        return new ProjectResponseDto(
                project.getId(),
                tenantSummary,
                project.getName(),
                project.getDescription(),
                owner != null ? owner.getId() : null,
                owner != null ? owner.getDisplayName() : null,
                team != null ? team.getId() : null,
                team != null ? team.getName() : null,
                project.getStatus(),
                project.getStartDate(),
                project.getTargetDate(),
                project.getCreatedAt(),
                project.getUpdatedAt());
    }

    public ProjectSummaryResponseDto toSummaryDto(Project project, ProjectTaskCountsDto taskCounts,
                                                  int progressPercent, Instant lastActivityAt) {
        User owner = project.getOwner();
        Team team = project.getTeam();

        return new ProjectSummaryResponseDto(
                project.getId(),
                project.getName(),
                project.getStatus(),
                team != null ? team.getId() : null,
                team != null ? team.getName() : null,
                owner != null ? owner.getId() : null,
                owner != null ? owner.getDisplayName() : null,
                project.getTargetDate(),
                taskCounts,
                progressPercent,
                lastActivityAt);
    }
}
