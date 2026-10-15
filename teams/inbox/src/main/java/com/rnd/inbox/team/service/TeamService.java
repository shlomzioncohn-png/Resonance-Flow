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
import com.rnd.inbox.shared.security.ActorScope;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.tenant.exception.TenantNotFoundException;
import com.rnd.inbox.tenant.repository.TenantRepository;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.exception.UserNotFoundException;
import com.rnd.inbox.user.repository.UserRepository;
import com.rnd.inbox.user.service.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class TeamService {

    private final TeamRepository teamRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final TeamMemberService teamMemberService;
    private final UserService userService;
    private final TeamMapper teamMapper;
    private final AuditService auditService;
    private final TeamAuthorization teamAuthorization;

    public TeamService(TeamRepository teamRepository, TenantRepository tenantRepository,
                        UserRepository userRepository, TeamMemberService teamMemberService,
                        UserService userService, TeamMapper teamMapper, AuditService auditService, TeamAuthorization teamAuthorization) {
        this.teamRepository = teamRepository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.teamMemberService = teamMemberService;
        this.userService = userService;
        this.teamMapper = teamMapper;
        this.auditService = auditService;
        this.teamAuthorization = teamAuthorization;
    }

    @Transactional
    public TeamResponseDto createTeam(TeamCreateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Tenant tenant = findTenantOrThrow(UUID.fromString(request.getTenantId()));
        teamAuthorization.assertCanCreateIn(teamAuthorization.scopeOf(actor), tenant);

        User teamLead = resolveUserInTenant(request.getTeamLeadId(), tenant);
        userService.promoteToTeamLeadIfEmployee(teamLead, actor);

        Team team = teamMapper.toEntity(request, tenant, teamLead);
        Team saved = teamRepository.save(team);
        teamMemberService.ensureMembership(saved, teamLead, actor);

        auditService.createAuditLog("TEAM_CREATED", "Team", saved.getId(), tenant, actor,
                auditSnapshot(saved), null);
        return teamMapper.toResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public TeamResponseDto getTeamById(UUID id, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Team team = findTeamOrThrow(id);
        teamAuthorization.assertWithinScope(teamAuthorization.scopeOf(actor), team);
        return teamMapper.toResponseDto(team);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<TeamResponseDto> listTeams(Pageable pageable, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Page<Team> teams = switch (teamAuthorization.scopeOf(actor)) {
            case ActorScope.All ignored -> teamRepository.findAll(pageable);
            case ActorScope.WithinTenant(UUID tenantId) -> teamRepository.findAllByTenant_Id(tenantId, pageable);
            case ActorScope.WithinTeamsLedBy ignored ->
                    throw new ForbiddenActionException("Actor has no valid scope for teams");
            case ActorScope.SelfOnly ignored ->
                    throw new ForbiddenActionException("Actor has no valid scope for teams");
        };
        return PageResponseDto.from(teams.map(teamMapper::toResponseDto));
    }

    @Transactional
    public TeamResponseDto updateTeam(UUID id, TeamUpdateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Team team = findTeamOrThrow(id);
        teamAuthorization.assertWithinScope(teamAuthorization.scopeOf(actor), team);

        User resolvedTeamLead = null;
        if (request.getTeamLeadId() != null) {
            resolvedTeamLead = resolveUserInTenant(request.getTeamLeadId(), team.getTenant());
            userService.promoteToTeamLeadIfEmployee(resolvedTeamLead, actor);
        }

        Map<String, Object> before = auditSnapshot(team);
        teamMapper.applyUpdate(team, request, resolvedTeamLead);
        Team saved = teamRepository.save(team);
        if (resolvedTeamLead != null) {
            teamMemberService.ensureMembership(saved, resolvedTeamLead, actor);
        }

        auditService.createUpdateAuditLog("TEAM_UPDATED", "Team", saved.getId(), saved.getTenant(), actor,
                before, auditSnapshot(saved), null);
        return teamMapper.toResponseDto(saved);
    }

    @Transactional
    public TeamResponseDto updateTeamStatus(UUID id, TeamStatusUpdateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Team team = findTeamOrThrow(id);

        Map<String, Object> before = auditSnapshot(team);
        teamMapper.applyStatusChange(team, TeamStatus.valueOf(request.getStatus()));
        Team saved = teamRepository.save(team);

        auditService.createUpdateAuditLog("TEAM_STATUS_UPDATED", "Team", saved.getId(), saved.getTenant(), actor,
                before, auditSnapshot(saved), null);
        return teamMapper.toResponseDto(saved);
    }

    private Team findTeamOrThrow(UUID id) {
        return teamRepository.findById(id)
                .orElseThrow(() -> new TeamNotFoundException(id));
    }

    private Tenant findTenantOrThrow(UUID id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new TenantNotFoundException(id));
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

    private Map<String, Object> auditSnapshot(Team team) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", team.getName());
        snapshot.put("teamLeadId", team.getTeamLead() != null ? team.getTeamLead().getId() : null);
        snapshot.put("status", team.getStatus().name());
        return snapshot;
    }
}
