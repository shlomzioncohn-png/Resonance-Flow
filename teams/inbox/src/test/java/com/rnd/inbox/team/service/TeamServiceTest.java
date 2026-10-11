package com.rnd.inbox.team.service;
import com.rnd.inbox.team.dto.TeamCreateRequestDto;
import com.rnd.inbox.team.dto.TeamResponseDto;
import com.rnd.inbox.team.dto.TeamStatusUpdateRequestDto;
import com.rnd.inbox.team.dto.TeamUpdateRequestDto;
import com.rnd.inbox.team.exception.TeamNotFoundException;
import com.rnd.inbox.team.mapper.TeamMapper;
import com.rnd.inbox.team.member.TeamMemberService;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.team.model.TeamStatus;
import com.rnd.inbox.team.repository.TeamRepository;
import com.rnd.inbox.team.security.TeamAuthorization;
import com.rnd.inbox.audit.AuditService;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.tenant.repository.TenantRepository;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.repository.UserRepository;
import com.rnd.inbox.user.service.UserService;
import com.rnd.inbox.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private UserRepository userRepository;

    // TeamMemberService has its own dedicated test file (TeamMemberServiceTest) - mocked here,
    // only its calls from TeamService are verified, not its internal correctness.
    @Mock
    private TeamMemberService teamMemberService;

    // Same for UserService: promoting an employee to team lead is its job, tested there.
    @Mock
    private UserService userService;

    @Mock
    private AuditService auditService;

    private final TeamMapper teamMapper = new TeamMapper(Clock.fixed(NOW, ZoneOffset.UTC));

    private TeamService teamService;

    private UUID tenantId;
    private Tenant tenant;
    private UUID actorId;
    private User actor;

    @BeforeEach
    void setUp() {
        teamService = new TeamService(teamRepository, tenantRepository, userRepository, teamMemberService,
                userService, teamMapper, auditService, new TeamAuthorization());

        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);

        actorId = UUID.randomUUID();
        actor = new User();
        actor.setId(actorId);
        actor.setTenant(tenant);
        actor.setRole(UserRole.TENANT_ADMIN);
    }

    private User userInTenant(UserRole role) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTenant(tenant);
        user.setRole(role);
        return user;
    }

    @Test
    void createTeam_promotesTheLeadAndEnsuresMembershipUnderTheActor() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        User lead = userInTenant(UserRole.EMPLOYEE);
        when(userRepository.findById(lead.getId())).thenReturn(Optional.of(lead));
        when(teamRepository.save(any(Team.class))).thenAnswer(invocation -> {
            Team team = invocation.getArgument(0);
            team.setId(UUID.randomUUID());
            return team;
        });

        TeamCreateRequestDto request = new TeamCreateRequestDto();
        request.setTenantId(tenantId.toString());
        request.setName("Platform Team");
        request.setTeamLeadId(lead.getId().toString());

        TeamResponseDto result = teamService.createTeam(request, actorId);

        assertThat(result.getName()).isEqualTo("Platform Team");
        verify(userService).promoteToTeamLeadIfEmployee(lead, actor);
        verify(teamMemberService).ensureMembership(any(Team.class), eq(lead), eq(actor));
        verify(auditService).createAuditLog(eq("TEAM_CREATED"), eq("Team"), any(), eq(tenant), eq(actor),
                any(), isNull());
    }

    @Test
    void createTeam_whenActorDoesNotExist_throwsUnauthenticatedException() {
        when(userRepository.findById(actorId)).thenReturn(Optional.empty());

        TeamCreateRequestDto request = new TeamCreateRequestDto();
        request.setTenantId(tenantId.toString());
        request.setName("Platform Team");
        request.setTeamLeadId(UUID.randomUUID().toString());

        assertThatThrownBy(() -> teamService.createTeam(request, actorId))
                .isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void createTeam_whenActorIsTenantAdminOfAnotherTenant_throwsForbiddenActionException() {
        actor.setTenant(new Tenant());
        actor.getTenant().setId(UUID.randomUUID());
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        TeamCreateRequestDto request = new TeamCreateRequestDto();
        request.setTenantId(tenantId.toString());
        request.setName("Platform Team");
        request.setTeamLeadId(UUID.randomUUID().toString());

        assertThatThrownBy(() -> teamService.createTeam(request, actorId))
                .isInstanceOf(ForbiddenActionException.class);

        verify(teamRepository, never()).save(any());
    }

    @Test
    void getTeamById_whenTeamIsInAnotherTenant_throwsForbiddenActionException() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        UUID teamId = UUID.randomUUID();
        Team team = new Team();
        team.setTenant(new Tenant());
        team.getTenant().setId(UUID.randomUUID());
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> teamService.getTeamById(teamId, actorId))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void getTeamById_whenNotFound_throwsTeamNotFoundException() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        UUID teamId = UUID.randomUUID();
        when(teamRepository.findById(teamId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.getTeamById(teamId, actorId))
                .isInstanceOf(TeamNotFoundException.class);
    }

    @Test
    void listTeams_whenActorIsSystemAdmin_returnsEveryTeam() {
        actor.setRole(UserRole.SYSTEM_ADMIN);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        Pageable pageable = PageRequest.of(0, 20);
        Team team = new Team();
        team.setTenant(tenant);
        Page<Team> page = new PageImpl<>(List.of(team), pageable, 1);
        when(teamRepository.findAll(pageable)).thenReturn(page);

        PageResponseDto<TeamResponseDto> result = teamService.listTeams(pageable, actorId);

        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(teamRepository, never()).findAllByTenant_Id(any(), any());
    }

    @Test
    void listTeams_whenActorIsEmployee_returnsOnlyOwnTenantTeams() {
        actor.setRole(UserRole.EMPLOYEE);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        Pageable pageable = PageRequest.of(0, 20);
        Team team = new Team();
        team.setTenant(tenant);
        Page<Team> page = new PageImpl<>(List.of(team), pageable, 1);
        when(teamRepository.findAllByTenant_Id(tenantId, pageable)).thenReturn(page);

        PageResponseDto<TeamResponseDto> result = teamService.listTeams(pageable, actorId);

        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(teamRepository, never()).findAll(pageable);
    }

    @Test
    void updateTeam_whenTeamLeadIdOmitted_doesNotResolveOrPromoteAnyone() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        UUID teamId = UUID.randomUUID();
        Team team = new Team();
        team.setId(teamId);
        team.setTenant(tenant);
        team.setStatus(TeamStatus.ACTIVE);
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));
        when(teamRepository.save(team)).thenReturn(team);

        TeamUpdateRequestDto request = new TeamUpdateRequestDto();
        request.setName("New Name");

        teamService.updateTeam(teamId, request, actorId);

        verify(teamMemberService, never()).ensureMembership(any(), any(), any());
        verifyNoInteractions(userService);
    }

    @Test
    void updateTeam_whenTeamLeadIdProvided_promotesAndEnsuresMembershipUnderTheActor() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        UUID teamId = UUID.randomUUID();
        Team team = new Team();
        team.setId(teamId);
        team.setTenant(tenant);
        team.setStatus(TeamStatus.ACTIVE);
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));
        when(teamRepository.save(team)).thenReturn(team);
        User newLead = userInTenant(UserRole.EMPLOYEE);
        when(userRepository.findById(newLead.getId())).thenReturn(Optional.of(newLead));

        TeamUpdateRequestDto request = new TeamUpdateRequestDto();
        request.setTeamLeadId(newLead.getId().toString());

        teamService.updateTeam(teamId, request, actorId);

        verify(userService).promoteToTeamLeadIfEmployee(newLead, actor);
        verify(teamMemberService).ensureMembership(team, newLead, actor);
    }

    @Test
    void updateTeam_whenTeamIsInAnotherTenant_throwsForbiddenActionException() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        UUID teamId = UUID.randomUUID();
        Team team = new Team();
        team.setTenant(new Tenant());
        team.getTenant().setId(UUID.randomUUID());
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));

        TeamUpdateRequestDto request = new TeamUpdateRequestDto();
        request.setName("New Name");

        assertThatThrownBy(() -> teamService.updateTeam(teamId, request, actorId))
                .isInstanceOf(ForbiddenActionException.class);

        verify(teamRepository, never()).save(any());
    }

    @Test
    void updateTeamStatus_persistsNewStatus() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        UUID teamId = UUID.randomUUID();
        Team team = new Team();
        team.setId(teamId);
        team.setTenant(tenant);
        team.setStatus(TeamStatus.ACTIVE);
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));
        when(teamRepository.save(team)).thenReturn(team);

        TeamStatusUpdateRequestDto request = new TeamStatusUpdateRequestDto();
        request.setStatus("ARCHIVED");

        TeamResponseDto result = teamService.updateTeamStatus(teamId, request, actorId);

        assertThat(result.getStatus()).isEqualTo(TeamStatus.ARCHIVED);
    }

    @Test
    void updateTeam_auditsTheValuesFromBeforeAndAfterTheChange() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        UUID teamId = UUID.randomUUID();
        Team team = new Team();
        team.setId(teamId);
        team.setTenant(tenant);
        team.setName("Old Name");
        team.setStatus(TeamStatus.ACTIVE);
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));
        when(teamRepository.save(team)).thenReturn(team);
        User newLead = userInTenant(UserRole.TEAM_LEAD);
        when(userRepository.findById(newLead.getId())).thenReturn(Optional.of(newLead));

        TeamUpdateRequestDto request = new TeamUpdateRequestDto();
        request.setName("New Name");
        request.setTeamLeadId(newLead.getId().toString());

        teamService.updateTeam(teamId, request, actorId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> beforeCaptor = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> afterCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).createUpdateAuditLog(eq("TEAM_UPDATED"), eq("Team"), eq(teamId), eq(tenant), eq(actor),
                beforeCaptor.capture(), afterCaptor.capture(), isNull());
        assertThat(beforeCaptor.getValue())
                .containsEntry("name", "Old Name")
                .containsEntry("teamLeadId", null);
        assertThat(afterCaptor.getValue())
                .containsEntry("name", "New Name")
                .containsEntry("teamLeadId", newLead.getId());
    }

    @Test
    void updateTeamStatus_auditsTheStatusFromBeforeAndAfterTheChange() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        UUID teamId = UUID.randomUUID();
        Team team = new Team();
        team.setId(teamId);
        team.setTenant(tenant);
        team.setStatus(TeamStatus.ACTIVE);
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));
        when(teamRepository.save(team)).thenReturn(team);

        TeamStatusUpdateRequestDto request = new TeamStatusUpdateRequestDto();
        request.setStatus("ARCHIVED");

        teamService.updateTeamStatus(teamId, request, actorId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> beforeCaptor = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> afterCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).createUpdateAuditLog(eq("TEAM_STATUS_UPDATED"), eq("Team"), eq(teamId), eq(tenant),
                eq(actor), beforeCaptor.capture(), afterCaptor.capture(), isNull());
        assertThat(beforeCaptor.getValue()).containsEntry("status", "ACTIVE");
        assertThat(afterCaptor.getValue()).containsEntry("status", "ARCHIVED");
    }
}
