package com.rnd.inbox.team.mapper;
import com.rnd.inbox.team.dto.TeamCreateRequestDto;
import com.rnd.inbox.team.dto.TeamResponseDto;
import com.rnd.inbox.team.dto.TeamUpdateRequestDto;
import com.rnd.inbox.team.member.TeamMember;
import com.rnd.inbox.team.member.TeamMemberResponseDto;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.team.model.TeamStatus;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TeamMapperTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    private TeamMapper teamMapper;
    private Tenant tenant;
    private User teamLead;

    @BeforeEach
    void setUp() {
        teamMapper = new TeamMapper(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Acme Inc");
        tenant.setSlug("acme");

        teamLead = new User();
        teamLead.setId(UUID.randomUUID());
    }

    private TeamCreateRequestDto createRequest() {
        TeamCreateRequestDto request = new TeamCreateRequestDto();
        request.setTenantId(tenant.getId().toString());
        request.setName("Platform Team");
        request.setTeamLeadId(teamLead.getId().toString());
        return request;
    }

    @Test
    void toEntity_buildsTeamFromCreateRequest_withActiveStatus() {
        Team team = teamMapper.toEntity(createRequest(), tenant, teamLead);

        assertThat(team.getTenant()).isSameAs(tenant);
        assertThat(team.getName()).isEqualTo("Platform Team");
        assertThat(team.getTeamLead()).isSameAs(teamLead);
        assertThat(team.getStatus()).isEqualTo(TeamStatus.ACTIVE);
        assertThat(team.getCreatedAt()).isEqualTo(FIXED_NOW);
        assertThat(team.getUpdatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void applyUpdate_overwritesProvidedFieldsAndBumpsUpdatedAt() {
        Team team = new Team();
        team.setName("Old Name");
        team.setTeamLead(teamLead);
        team.setStatus(TeamStatus.ACTIVE);
        team.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        team.setUpdatedAt(Instant.parse("2025-01-01T00:00:00Z"));

        User newLead = new User();
        newLead.setId(UUID.randomUUID());

        TeamUpdateRequestDto request = new TeamUpdateRequestDto();
        request.setName("New Name");
        request.setTeamLeadId(newLead.getId().toString());

        teamMapper.applyUpdate(team, request, newLead);

        assertThat(team.getName()).isEqualTo("New Name");
        assertThat(team.getTeamLead()).isSameAs(newLead);
        assertThat(team.getUpdatedAt()).isEqualTo(FIXED_NOW);

        // applyUpdate must never touch these
        assertThat(team.getStatus()).isEqualTo(TeamStatus.ACTIVE);
        assertThat(team.getCreatedAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
    }

    @Test
    void applyUpdate_whenTeamLeadIdOmitted_preservesExistingTeamLead() {
        Team team = new Team();
        team.setName("Existing Name");
        team.setTeamLead(teamLead);

        TeamUpdateRequestDto request = new TeamUpdateRequestDto();
        request.setName("New Name");
        // teamLeadId intentionally left null - not part of this update

        teamMapper.applyUpdate(team, request, null);

        assertThat(team.getName()).isEqualTo("New Name");
        assertThat(team.getTeamLead()).isSameAs(teamLead);
    }

    @Test
    void applyStatusChange_setsStatusAndBumpsUpdatedAt() {
        Team team = new Team();
        team.setStatus(TeamStatus.ACTIVE);
        team.setUpdatedAt(Instant.parse("2025-01-01T00:00:00Z"));

        teamMapper.applyStatusChange(team, TeamStatus.ARCHIVED);

        assertThat(team.getStatus()).isEqualTo(TeamStatus.ARCHIVED);
        assertThat(team.getUpdatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void toResponseDto_mapsAllFields() {
        Team team = new Team();
        team.setId(UUID.randomUUID());
        team.setTenant(tenant);
        team.setName("Platform Team");
        team.setTeamLead(teamLead);
        team.setStatus(TeamStatus.ACTIVE);
        team.setCreatedAt(FIXED_NOW);
        team.setUpdatedAt(FIXED_NOW);

        TeamResponseDto dto = teamMapper.toResponseDto(team);

        assertThat(dto.getId()).isEqualTo(team.getId());
        assertThat(dto.getTenant().getId()).isEqualTo(tenant.getId());
        assertThat(dto.getName()).isEqualTo("Platform Team");
        assertThat(dto.getTeamLeadId()).isEqualTo(teamLead.getId());
        assertThat(dto.getStatus()).isEqualTo(TeamStatus.ACTIVE);
    }

    @Test
    void toMemberEntity_buildsCompositeKeyFromTeamAndUser() {
        Team team = new Team();
        team.setId(UUID.randomUUID());
        User user = new User();
        user.setId(UUID.randomUUID());

        TeamMember member = teamMapper.toMemberEntity(team, user);

        assertThat(member.getId().getTeamId()).isEqualTo(team.getId());
        assertThat(member.getId().getUserId()).isEqualTo(user.getId());
        assertThat(member.getTeam()).isSameAs(team);
        assertThat(member.getUser()).isSameAs(user);
        assertThat(member.getJoinedAt()).isEqualTo(FIXED_NOW);
        assertThat(member.getLeftAt()).isNull();
    }

    @Test
    void toMemberResponseDto_mapsFields() {
        User user = new User();
        user.setId(UUID.randomUUID());
        TeamMember member = new TeamMember();
        member.setUser(user);
        member.setJoinedAt(FIXED_NOW);

        TeamMemberResponseDto dto = teamMapper.toMemberResponseDto(member);

        assertThat(dto.getUserId()).isEqualTo(user.getId());
        assertThat(dto.getJoinedAt()).isEqualTo(FIXED_NOW);
        assertThat(dto.getLeftAt()).isNull();
    }
}
