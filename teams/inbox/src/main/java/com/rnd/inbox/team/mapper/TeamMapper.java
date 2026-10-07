package com.rnd.inbox.team.mapper;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.team.dto.TeamCreateRequestDto;
import com.rnd.inbox.team.dto.TeamResponseDto;
import com.rnd.inbox.team.dto.TeamUpdateRequestDto;
import com.rnd.inbox.team.member.TeamMember;
import com.rnd.inbox.team.member.TeamMemberId;
import com.rnd.inbox.team.member.TeamMemberResponseDto;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.team.model.TeamStatus;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.tenant.dto.TenantSummaryDto;
import com.rnd.inbox.user.model.User;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class TeamMapper {

    private final Clock clock;

    public TeamMapper(Clock clock) {
        this.clock = clock;
    }

    public Team toEntity(TeamCreateRequestDto dto, Tenant tenant, User teamLead) {
        Team team = new Team();
        team.setTenant(tenant);
        team.setName(dto.getName());
        team.setTeamLead(teamLead);
        team.setStatus(TeamStatus.ACTIVE);
        Instant now = Instant.now(clock);
        team.setCreatedAt(now);
        team.setUpdatedAt(now);
        return team;
    }

    // resolvedTeamLead is only used when dto.getTeamLeadId() is non-null - the Service resolves
    // it beforehand (a repository concern the Mapper never does), same convention as Task's
    // applyUpdate for its project/owner references.
    public void applyUpdate(Team team, TeamUpdateRequestDto dto, User resolvedTeamLead) {
        if (dto.getName() != null) {
            team.setName(dto.getName());
        }
        if (dto.getTeamLeadId() != null) {
            team.setTeamLead(resolvedTeamLead);
        }
        team.setUpdatedAt(Instant.now(clock));
    }

    public void applyStatusChange(Team team, TeamStatus newStatus) {
        team.setStatus(newStatus);
        team.setUpdatedAt(Instant.now(clock));
    }

    public TeamResponseDto toResponseDto(Team team) {
        Tenant tenant = team.getTenant();
        TenantSummaryDto tenantSummary = tenant != null
                ? new TenantSummaryDto(tenant.getId(), tenant.getName(), tenant.getSlug())
                : null;

        return new TeamResponseDto(
                team.getId(),
                tenantSummary,
                team.getName(),
                team.getTeamLead() != null ? team.getTeamLead().getId() : null,
                team.getStatus(),
                team.getCreatedAt(),
                team.getUpdatedAt());
    }

    public TeamMember toMemberEntity(Team team, User user) {
        TeamMember member = new TeamMember();
        member.setId(new TeamMemberId(team.getId(), user.getId()));
        member.setTeam(team);
        member.setUser(user);
        member.setJoinedAt(Instant.now(clock));
        return member;
    }

    public TeamMemberResponseDto toMemberResponseDto(TeamMember member) {
        return new TeamMemberResponseDto(
                member.getUser().getId(),
                member.getJoinedAt(),
                member.getLeftAt());
    }
}
