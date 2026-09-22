package com.rnd.inbox.team;

import com.rnd.inbox.audit.AuditService;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.tenant.Tenant;
import com.rnd.inbox.user.User;
import com.rnd.inbox.user.UserRepository;
import com.rnd.inbox.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamMemberServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @Mock
    private AuditService auditService;

    private final TeamMapper teamMapper = new TeamMapper(Clock.fixed(NOW, ZoneOffset.UTC));

    private TeamMemberService teamMemberService;

    private UUID tenantId;
    private Tenant tenant;
    private UUID actorId;
    private User actor;
    private UUID teamId;
    private Team team;

    @BeforeEach
    void setUp() {
        teamMemberService = new TeamMemberService(teamRepository, userRepository, teamMemberRepository,
                teamMapper, auditService, new TeamAuthorization(), Clock.fixed(NOW, ZoneOffset.UTC));

        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);

        actorId = UUID.randomUUID();
        actor = new User();
        actor.setId(actorId);
        actor.setTenant(tenant);
        actor.setRole(UserRole.TENANT_ADMIN);

        teamId = UUID.randomUUID();
        team = new Team();
        team.setId(teamId);
        team.setTenant(tenant);
    }

    private User userInTenant() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTenant(tenant);
        return user;
    }

    @Test
    void addMember_whenNotAlreadyAMember_savesNewMembership() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));
        User user = userInTenant();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(teamMemberRepository.findByTeam_IdAndUser_IdAndLeftAtIsNull(teamId, user.getId()))
                .thenReturn(Optional.empty());
        when(teamMemberRepository.save(any(TeamMember.class))).thenAnswer(inv -> inv.getArgument(0));

        TeamMemberRequestDto request = new TeamMemberRequestDto();
        request.setUserId(user.getId().toString());

        TeamMemberResponseDto result = teamMemberService.addMember(teamId, request, actorId);

        assertThat(result.getUserId()).isEqualTo(user.getId());
        verify(auditService).createAuditLog(org.mockito.ArgumentMatchers.eq("TEAM_MEMBER_ADDED"),
                org.mockito.ArgumentMatchers.eq("Team"), org.mockito.ArgumentMatchers.eq(teamId),
                org.mockito.ArgumentMatchers.eq(tenant), org.mockito.ArgumentMatchers.eq(actor),
                any(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void addMember_whenAlreadyAnActiveMember_throwsDuplicateTeamMembershipException() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));
        User user = userInTenant();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(teamMemberRepository.findByTeam_IdAndUser_IdAndLeftAtIsNull(teamId, user.getId()))
                .thenReturn(Optional.of(new TeamMember()));

        TeamMemberRequestDto request = new TeamMemberRequestDto();
        request.setUserId(user.getId().toString());

        assertThatThrownBy(() -> teamMemberService.addMember(teamId, request, actorId))
                .isInstanceOf(DuplicateTeamMembershipException.class);

        verify(teamMemberRepository, never()).save(any());
    }

    @Test
    void addMember_whenUserOutsideTeamsTenant_throwsForbiddenActionException() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));
        User outsider = new User();
        outsider.setId(UUID.randomUUID());
        outsider.setTenant(new Tenant());
        outsider.getTenant().setId(UUID.randomUUID());
        when(userRepository.findById(outsider.getId())).thenReturn(Optional.of(outsider));

        TeamMemberRequestDto request = new TeamMemberRequestDto();
        request.setUserId(outsider.getId().toString());

        assertThatThrownBy(() -> teamMemberService.addMember(teamId, request, actorId))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void addMember_whenActorDoesNotExist_throwsUnauthenticatedException() {
        when(userRepository.findById(actorId)).thenReturn(Optional.empty());

        TeamMemberRequestDto request = new TeamMemberRequestDto();
        request.setUserId(UUID.randomUUID().toString());

        assertThatThrownBy(() -> teamMemberService.addMember(teamId, request, actorId))
                .isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void removeMember_whenActiveMembershipExists_setsLeftAt() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));
        UUID userId = UUID.randomUUID();
        TeamMember membership = new TeamMember();
        User user = new User();
        user.setId(userId);
        membership.setUser(user);
        when(teamMemberRepository.findByTeam_IdAndUser_IdAndLeftAtIsNull(teamId, userId))
                .thenReturn(Optional.of(membership));
        when(teamMemberRepository.save(membership)).thenReturn(membership);

        teamMemberService.removeMember(teamId, userId, actorId);

        assertThat(membership.getLeftAt()).isEqualTo(NOW);
        verify(teamMemberRepository).save(membership);
    }

    @Test
    void removeMember_whenNotAnActiveMember_throwsTeamMembershipNotFoundException() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));
        UUID userId = UUID.randomUUID();
        when(teamMemberRepository.findByTeam_IdAndUser_IdAndLeftAtIsNull(teamId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> teamMemberService.removeMember(teamId, userId, actorId))
                .isInstanceOf(TeamMembershipNotFoundException.class);
    }

    @Test
    void listMembers_returnsActiveMembersOnly() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));
        User user = new User();
        user.setId(UUID.randomUUID());
        TeamMember member = new TeamMember();
        member.setUser(user);
        member.setJoinedAt(NOW);
        when(teamMemberRepository.findAllByTeam_IdAndLeftAtIsNull(teamId)).thenReturn(List.of(member));

        List<TeamMemberResponseDto> result = teamMemberService.listMembers(teamId, actorId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserId()).isEqualTo(user.getId());
    }

    @Test
    void listMembers_whenTeamIsInAnotherTenant_throwsForbiddenActionException() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        team.setTenant(new Tenant());
        team.getTenant().setId(UUID.randomUUID());
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> teamMemberService.listMembers(teamId, actorId))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void ensureMembership_whenNotAlreadyAMember_savesNewMembership() {
        User lead = userInTenant();
        when(teamMemberRepository.findByTeam_IdAndUser_IdAndLeftAtIsNull(teamId, lead.getId()))
                .thenReturn(Optional.empty());

        teamMemberService.ensureMembership(team, lead);

        verify(teamMemberRepository).save(any(TeamMember.class));
    }

    @Test
    void ensureMembership_whenAlreadyAMember_doesNotSaveDuplicate() {
        User lead = userInTenant();
        when(teamMemberRepository.findByTeam_IdAndUser_IdAndLeftAtIsNull(teamId, lead.getId()))
                .thenReturn(Optional.of(new TeamMember()));

        teamMemberService.ensureMembership(team, lead);

        verify(teamMemberRepository, never()).save(any());
    }
}
