package com.rnd.inbox.team.member;
import com.rnd.inbox.team.exception.TeamNotFoundException;
import com.rnd.inbox.team.mapper.TeamMapper;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.team.repository.TeamRepository;
import com.rnd.inbox.team.security.TeamAuthorization;
import com.rnd.inbox.team.service.TeamService;
import com.rnd.inbox.audit.AuditService;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.exception.UserNotFoundException;
import com.rnd.inbox.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TeamMemberService {

    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamMapper teamMapper;
    private final AuditService auditService;
    private final TeamAuthorization teamAuthorization;
    private final Clock clock;

    public TeamMemberService(TeamRepository teamRepository, UserRepository userRepository,
                              TeamMemberRepository teamMemberRepository, TeamMapper teamMapper,
                              AuditService auditService, TeamAuthorization teamAuthorization, Clock clock) {
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.teamMapper = teamMapper;
        this.auditService = auditService;
        this.teamAuthorization = teamAuthorization;
        this.clock = clock;
    }

    @Transactional
    public TeamMemberResponseDto addMember(UUID teamId, TeamMemberRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Team team = findTeamOrThrow(teamId);

        User user = resolveUserInTenant(request.getUserId(), team.getTenant());
        if (teamMemberRepository.findByTeam_IdAndUser_IdAndLeftAtIsNull(teamId, user.getId()).isPresent()) {
            throw new DuplicateTeamMembershipException(teamId, user.getId());
        }

        TeamMember member = teamMapper.toMemberEntity(team, user);
        TeamMember saved = teamMemberRepository.save(member);

        auditService.createAuditLog("TEAM_MEMBER_ADDED", "Team", teamId, team.getTenant(), actor,
                memberSnapshot(saved), null);
        return teamMapper.toMemberResponseDto(saved);
    }

    @Transactional
    public void removeMember(UUID teamId, UUID userId, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Team team = findTeamOrThrow(teamId);

        TeamMember membership = teamMemberRepository.findByTeam_IdAndUser_IdAndLeftAtIsNull(teamId, userId)
                .orElseThrow(() -> new TeamMembershipNotFoundException(teamId, userId));
        membership.setLeftAt(Instant.now(clock));
        TeamMember saved = teamMemberRepository.save(membership);

        auditService.createAuditLog("TEAM_MEMBER_REMOVED", "Team", teamId, team.getTenant(), actor,
                memberSnapshot(saved), null);
    }

    @Transactional(readOnly = true)
    public List<TeamMemberResponseDto> listMembers(UUID teamId, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Team team = findTeamOrThrow(teamId);
        teamAuthorization.assertWithinScope(teamAuthorization.scopeOf(actor), team);

        return teamMemberRepository.findAllByTeam_IdAndLeftAtIsNull(teamId).stream()
                .map(teamMapper::toMemberResponseDto)
                .toList();
    }

    // Called from TeamService (create/update, when a team_lead_id is set) - a team lead is
    // always considered a member of the team they lead, per the decision that appointing someone
    // as team_lead_id should not require a separate call to also add them as a member. Public
    // (not a REST endpoint) since TeamService now lives in a sibling package after the layered
    // sub-package split.
    @Transactional
    public void ensureMembership(Team team, User user) {
        boolean alreadyMember = teamMemberRepository
                .findByTeam_IdAndUser_IdAndLeftAtIsNull(team.getId(), user.getId())
                .isPresent();
        if (!alreadyMember) {
            teamMemberRepository.save(teamMapper.toMemberEntity(team, user));
        }
    }

    private Team findTeamOrThrow(UUID id) {
        return teamRepository.findById(id)
                .orElseThrow(() -> new TeamNotFoundException(id));
    }

    private User findActorOrThrow(UUID actorId) {
        return userRepository.findById(actorId)
                .orElseThrow(() -> new UnauthenticatedException("Actor " + actorId + " does not exist"));
    }

    private User resolveUserInTenant(String rawId, Tenant tenant) {
        UUID id = UUID.fromString(rawId);
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        if (!user.getTenant().getId().equals(tenant.getId())) {
            throw new ForbiddenActionException("User " + id + " does not belong to tenant " + tenant.getId());
        }
        return user;
    }

    private Map<String, Object> memberSnapshot(TeamMember member) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("userId", member.getUser().getId());
        snapshot.put("joinedAt", member.getJoinedAt());
        snapshot.put("leftAt", member.getLeftAt());
        return snapshot;
    }
}
