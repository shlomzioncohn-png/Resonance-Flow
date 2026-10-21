package com.rnd.inbox.project.service;

import com.rnd.inbox.audit.AuditService;
import com.rnd.inbox.project.Project;
import com.rnd.inbox.project.ProjectNotFoundException;
import com.rnd.inbox.project.ProjectRepository;
import com.rnd.inbox.project.ProjectTaskStats;
import com.rnd.inbox.project.dto.ProjectCreateRequestDto;
import com.rnd.inbox.project.dto.ProjectFilterDto;
import com.rnd.inbox.project.dto.ProjectResponseDto;
import com.rnd.inbox.project.dto.ProjectSummaryResponseDto;
import com.rnd.inbox.project.dto.ProjectTaskCountsDto;
import com.rnd.inbox.project.dto.ProjectUpdateRequestDto;
import com.rnd.inbox.project.exception.InvalidProjectDatesException;
import com.rnd.inbox.project.exception.InvalidProjectSortException;
import com.rnd.inbox.project.exception.ProjectHasTasksException;
import com.rnd.inbox.project.mapper.ProjectMapper;
import com.rnd.inbox.project.security.ProjectAuthorization;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.shared.security.ActorScope;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.team.exception.TeamNotFoundException;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.team.repository.TeamRepository;
import com.rnd.inbox.tenant.exception.TenantNotFoundException;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.tenant.repository.TenantRepository;
import com.rnd.inbox.user.exception.UserNotFoundException;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private static final Map<String, String> SORTABLE_PROPERTIES = sortableProperties();

    private static final Set<TaskStatus> OPEN_STATUSES =
            Set.of(TaskStatus.NEW, TaskStatus.IN_REVIEW, TaskStatus.APPROVED);
    private static final Set<TaskStatus> CLOSED_WITHOUT_DONE_STATUSES =
            Set.of(TaskStatus.REJECTED, TaskStatus.CANCELLED);
    private static final Set<TaskStatus> FINISHED_STATUSES =
            Set.of(TaskStatus.DONE, TaskStatus.REJECTED, TaskStatus.CANCELLED);

    private final ProjectRepository projectRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final ProjectMapper projectMapper;
    private final ProjectAuthorization projectAuthorization;
    private final AuditService auditService;
    private final Clock clock;

    public ProjectService(ProjectRepository projectRepository, TenantRepository tenantRepository,
                          UserRepository userRepository, TeamRepository teamRepository,
                          ProjectMapper projectMapper, ProjectAuthorization projectAuthorization,
                          AuditService auditService, Clock clock) {
        this.projectRepository = projectRepository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.projectMapper = projectMapper;
        this.projectAuthorization = projectAuthorization;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public ProjectResponseDto createProject(ProjectCreateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Tenant tenant = findTenantOrThrow(UUID.fromString(request.getTenantId()));
        projectAuthorization.assertCanCreateIn(projectAuthorization.scopeOf(actor), tenant);

        User owner = resolveUserInTenant(request.getOwnerId(), tenant);
        Team team = resolveTeamInTenant(request.getTeamId(), tenant);
        assertDatesInOrder(request.getStartDate(), request.getTargetDate());

        Project saved = projectRepository.save(projectMapper.toEntity(request, tenant, owner, team));

        auditService.createAuditLog("PROJECT_CREATED", "Project", saved.getId(), tenant, actor,
                auditSnapshot(saved), null);
        log.info("Project created: projectId={}, tenantId={}", saved.getId(), tenant.getId());
        return projectMapper.toResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public ProjectResponseDto getProjectById(UUID id, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Project project = findProjectOrThrow(id);
        projectAuthorization.assertWithinScope(projectAuthorization.scopeOf(actor), project);
        return projectMapper.toResponseDto(project);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<ProjectResponseDto> listProjects(ProjectFilterDto filter, Pageable pageable,
                                                            UUID actorId) {
        Page<Project> projects = findProjectsInScope(filter, pageable, actorId);
        return PageResponseDto.from(projects.map(projectMapper::toResponseDto));
    }

    @Transactional(readOnly = true)
    public PageResponseDto<ProjectSummaryResponseDto> listProjectSummaries(ProjectFilterDto filter,
                                                                           Pageable pageable, UUID actorId) {
        Page<Project> projects = findProjectsInScope(filter, pageable, actorId);
        Map<UUID, ProjectTaskStats> statsByProjectId = findTaskStats(projects.getContent());
        return PageResponseDto.from(projects.map(project -> toSummary(project, statsByProjectId.get(project.getId()))));
    }

    @Transactional
    public ProjectResponseDto updateProject(UUID id, ProjectUpdateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Project project = findProjectOrThrow(id);
        projectAuthorization.assertWithinScope(projectAuthorization.scopeOf(actor), project);

        User resolvedOwner = request.getOwnerId().isPresent()
                ? resolveUserInTenant(request.getOwnerId().get(), project.getTenant())
                : null;
        Team resolvedTeam = request.getTeamId().isPresent()
                ? resolveTeamInTenant(request.getTeamId().get(), project.getTenant())
                : null;

        assertDatesInOrder(
                request.getStartDate().isPresent() ? request.getStartDate().get() : project.getStartDate(),
                request.getTargetDate().isPresent() ? request.getTargetDate().get() : project.getTargetDate());

        Map<String, Object> before = auditSnapshot(project);
        projectMapper.applyUpdate(project, request, resolvedOwner, resolvedTeam);
        Project saved = projectRepository.save(project);

        auditService.createUpdateAuditLog("PROJECT_UPDATED", "Project", saved.getId(), saved.getTenant(), actor,
                before, auditSnapshot(saved), null);
        return projectMapper.toResponseDto(saved);
    }

    @Transactional
    public void deleteProject(UUID id, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Project project = findProjectOrThrow(id);
        projectAuthorization.assertWithinScope(projectAuthorization.scopeOf(actor), project);

        long taskCount = projectRepository.countTasksByProjectId(id);
        if (taskCount > 0) {
            log.warn("Project delete rejected: projectId={} has {} tasks", id, taskCount);
            throw new ProjectHasTasksException(id, taskCount);
        }

        Map<String, Object> before = auditSnapshot(project);
        projectRepository.delete(project);

        auditService.createUpdateAuditLog("PROJECT_DELETED", "Project", id, project.getTenant(), actor,
                before, Map.of(), null);
        log.info("Project deleted: projectId={}", id);
    }

    private Page<Project> findProjectsInScope(ProjectFilterDto filter, Pageable pageable, UUID actorId) {
        Pageable sortedPageable = withSupportedSort(pageable);
        User actor = findActorOrThrow(actorId);
        boolean bypassTenantScope;
        UUID tenantId;
        switch (projectAuthorization.scopeOf(actor)) {
            case ActorScope.All ignored -> {
                bypassTenantScope = true;
                tenantId = null;
            }
            case ActorScope.WithinTenant(UUID id) -> {
                bypassTenantScope = false;
                tenantId = id;
            }
            case ActorScope.WithinTeamsLedBy ignored ->
                    throw new ForbiddenActionException("Actor has no valid scope for projects");
            case ActorScope.SelfOnly ignored ->
                    throw new ForbiddenActionException("Actor has no valid scope for projects");
        }
        String searchPattern = filter.getSearch() != null ? "%" + filter.getSearch().toLowerCase() + "%" : null;
        return projectRepository.findFiltered(
                bypassTenantScope,
                tenantId,
                searchPattern != null,
                searchPattern,
                filter.getStatus() != null,
                filter.getStatus(),
                filter.getTeamId() != null,
                filter.getTeamId(),
                filter.getOwnerId() != null,
                filter.getOwnerId(),
                sortedPageable);
    }

    private Map<UUID, ProjectTaskStats> findTaskStats(List<Project> projects) {
        if (projects.isEmpty()) {
            return Map.of();
        }
        List<UUID> projectIds = projects.stream().map(Project::getId).toList();
        return projectRepository.findTaskStatsByProjectIds(
                        projectIds,
                        OPEN_STATUSES,
                        TaskStatus.IN_PROGRESS,
                        TaskStatus.SNOOZED,
                        TaskStatus.DONE,
                        CLOSED_WITHOUT_DONE_STATUSES,
                        FINISHED_STATUSES,
                        Instant.now(clock))
                .stream()
                .collect(Collectors.toMap(ProjectTaskStats::getProjectId, Function.identity()));
    }

    private ProjectSummaryResponseDto toSummary(Project project, ProjectTaskStats stats) {
        if (stats == null) {
            return projectMapper.toSummaryDto(project, new ProjectTaskCountsDto(0, 0, 0, 0, 0, 0, 0),
                    0, project.getUpdatedAt());
        }
        ProjectTaskCountsDto counts = new ProjectTaskCountsDto(
                stats.getTotal(),
                stats.getOpen(),
                stats.getInProgress(),
                stats.getSnoozed(),
                stats.getDone(),
                stats.getClosedWithoutDone(),
                stats.getOverdue());
        Instant lastActivityAt = stats.getLastTaskUpdatedAt().isAfter(project.getUpdatedAt())
                ? stats.getLastTaskUpdatedAt()
                : project.getUpdatedAt();
        return projectMapper.toSummaryDto(project, counts, progressPercent(counts), lastActivityAt);
    }

    private int progressPercent(ProjectTaskCountsDto counts) {
        long relevant = counts.getTotal() - counts.getClosedWithoutDone();
        if (relevant == 0) {
            return 0;
        }
        return (int) Math.round(counts.getDone() * 100.0 / relevant);
    }

    private void assertDatesInOrder(LocalDate startDate, LocalDate targetDate) {
        if (startDate != null && targetDate != null && targetDate.isBefore(startDate)) {
            throw new InvalidProjectDatesException(startDate, targetDate);
        }
    }


    private Pageable withSupportedSort(Pageable pageable) {
        if (pageable.isUnpaged()) {
            return pageable;
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order requested : pageable.getSort()) {
            String property = SORTABLE_PROPERTIES.get(requested.getProperty());
            if (property == null) {
                throw new InvalidProjectSortException(requested.getProperty(), SORTABLE_PROPERTIES.keySet());
            }
            orders.add(new Sort.Order(requested.getDirection(), property));
        }
        if (orders.isEmpty()) {
            orders.add(Sort.Order.desc("createdAt"));
        }
        orders.add(new Sort.Order(orders.get(orders.size() - 1).getDirection(), "id"));
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orders));
    }

    private Project findProjectOrThrow(UUID id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new ProjectNotFoundException(id));
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
        if (rawId == null) {
            return null;
        }
        UUID id = UUID.fromString(rawId);
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        if (!user.getTenant().getId().equals(tenant.getId())) {
            throw new ForbiddenActionException("User " + id + " does not belong to tenant " + tenant.getId());
        }
        return user;
    }

    private Team resolveTeamInTenant(String rawId, Tenant tenant) {
        if (rawId == null) {
            return null;
        }
        UUID id = UUID.fromString(rawId);
        Team team = teamRepository.findById(id)
                .orElseThrow(() -> new TeamNotFoundException(id));
        if (!team.getTenant().getId().equals(tenant.getId())) {
            throw new ForbiddenActionException("Team " + id + " does not belong to tenant " + tenant.getId());
        }
        return team;
    }

    private Map<String, Object> auditSnapshot(Project project) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", project.getName());
        snapshot.put("description", project.getDescription());
        snapshot.put("ownerId", project.getOwner() != null ? project.getOwner().getId() : null);
        snapshot.put("teamId", project.getTeam() != null ? project.getTeam().getId() : null);
        snapshot.put("status", project.getStatus().name());
        snapshot.put("startDate", project.getStartDate() != null ? project.getStartDate().toString() : null);
        snapshot.put("targetDate", project.getTargetDate() != null ? project.getTargetDate().toString() : null);
        return snapshot;
    }

    private static Map<String, String> sortableProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("name", "name");
        properties.put("targetDate", "targetDate");
        properties.put("target_date", "targetDate");
        properties.put("createdAt", "createdAt");
        properties.put("created_at", "createdAt");
        return properties;
    }
}
