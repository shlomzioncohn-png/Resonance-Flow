package com.rnd.inbox.task.service;
import com.rnd.inbox.task.assignment.Assignment;
import com.rnd.inbox.task.assignment.AssignmentRepository;
import com.rnd.inbox.task.controller.TaskController;
import com.rnd.inbox.task.dto.TaskCreateRequestDto;
import com.rnd.inbox.task.dto.TaskFilterDto;
import com.rnd.inbox.task.dto.TaskResponseDto;
import com.rnd.inbox.task.dto.TaskStatusUpdateRequestDto;
import com.rnd.inbox.task.dto.TaskUpdateRequestDto;
import com.rnd.inbox.task.event.TaskCompletedPayload;
import com.rnd.inbox.task.event.TaskCreatedPayload;
import com.rnd.inbox.task.event.TaskUpdatedPayload;
import com.rnd.inbox.task.exception.InvalidTaskFilterException;
import com.rnd.inbox.task.exception.InvalidTaskSortException;
import com.rnd.inbox.task.exception.InvalidTaskStatusTransitionException;
import com.rnd.inbox.task.exception.TaskNotFoundException;
import com.rnd.inbox.task.mapper.TaskMapper;
import com.rnd.inbox.task.mapper.TaskRestMapper;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.repository.TaskRepository;
import com.rnd.inbox.task.security.TaskAuthorization;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.inbox.audit.AuditService;
import com.rnd.inbox.outbox.OutboxEvent;
import com.rnd.inbox.outbox.OutboxEventRepository;
import com.rnd.inbox.project.Project;
import com.rnd.inbox.project.ProjectNotFoundException;
import com.rnd.inbox.project.ProjectRepository;
import com.rnd.inbox.scheduling.SchedulingEventDto;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.event.EventType;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.shared.security.ActorScope;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.tenant.exception.TenantNotFoundException;
import com.rnd.inbox.tenant.repository.TenantRepository;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.exception.UserNotFoundException;
import com.rnd.inbox.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class TaskService {

    // Request name -> Task property. Both spellings of a two-word name are accepted: the JSON
    // this API returns is snake_case, the Task properties are camelCase.
    private static final Map<String, String> SORTABLE_PROPERTIES = sortableProperties();

    private static Map<String, String> sortableProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("deadline", "deadline");
        properties.put("priorityScore", "priorityScore");
        properties.put("priority_score", "priorityScore");
        properties.put("createdAt", "createdAt");
        properties.put("created_at", "createdAt");
        return properties;
    }

    private static final Map<TaskStatus, Set<TaskStatus>> FORBIDDEN_STATUS_TRANSITIONS = Map.of(
            TaskStatus.DONE, Set.copyOf(EnumSet.complementOf(EnumSet.of(TaskStatus.DONE))));

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final TaskRepository taskRepository;
    private final AuditService auditService;
    private final OutboxEventRepository outboxEventRepository;
    private final AssignmentRepository assignmentRepository;
    private final TaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TenantRepository tenantRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final TaskAuthorization taskAuthorization;
    private final TaskRestMapper taskRestMapper;

    public TaskService(TaskRepository taskRepository,
                       AuditService auditService,
                       OutboxEventRepository outboxEventRepository,
                       AssignmentRepository assignmentRepository,
                       TaskMapper taskMapper,
                       ObjectMapper objectMapper,
                       Clock clock,
                       TenantRepository tenantRepository,
                       ProjectRepository projectRepository,
                       UserRepository userRepository,
                       TaskAuthorization taskAuthorization,
                       TaskRestMapper taskRestMapper) {
        this.taskRepository = taskRepository;
        this.auditService = auditService;
        this.outboxEventRepository = outboxEventRepository;
        this.assignmentRepository = assignmentRepository;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tenantRepository = tenantRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.taskAuthorization = taskAuthorization;
        this.taskRestMapper = taskRestMapper;
    }

    // ---- REST-facing methods (TaskController) ----

    @Transactional
    public TaskResponseDto createTaskFromRequest(TaskCreateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Tenant tenant = findTenantOrThrow(UUID.fromString(request.getTenantId()));
        taskAuthorization.assertCanCreateIn(taskAuthorization.scopeOf(actor), tenant);

        Project project = resolveProjectInTenant(request.getProjectId(), tenant);
        User owner = request.getOwnerId() != null
                ? resolveUserInTenant(request.getOwnerId(), tenant)
                : actor;

        Task task = taskRestMapper.toEntity(request, tenant, project, owner, actor);
        Task saved = persistNewTask(task, List.of(), null, actor);
        return taskRestMapper.toResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public TaskResponseDto getTaskById(UUID id, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Task task = findTaskOrThrow(id);
        taskAuthorization.assertWithinScope(taskAuthorization.scopeOf(actor), task);
        return taskRestMapper.toResponseDto(task);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<TaskResponseDto> listTasks(TaskFilterDto filter, Pageable pageable, UUID actorId) {
        validateFilter(filter);
        Pageable sortedPageable = withSupportedSort(pageable);
        User actor = findActorOrThrow(actorId);
        // bypassTenantScope comes only from this explicit role check - never from tenantId
        // possibly being null - so a bug that left tenantId null for a non-admin fails closed
        // (matches nothing) instead of silently granting cross-tenant access.
        boolean bypassTenantScope;
        UUID tenantId;
        switch (taskAuthorization.scopeOf(actor)) {
            case ActorScope.All ignored -> {
                bypassTenantScope = true;
                tenantId = null;
            }
            case ActorScope.WithinTenant(UUID id) -> {
                bypassTenantScope = false;
                tenantId = id;
            }
            case ActorScope.WithinTeamsLedBy ignored ->
                    throw new ForbiddenActionException("Actor has no valid scope for tasks");
            case ActorScope.SelfOnly ignored ->
                    throw new ForbiddenActionException("Actor has no valid scope for tasks");
        }
        String searchPattern = filter.getSearch() != null ? "%" + filter.getSearch().toLowerCase() + "%" : null;
        Page<Task> tasks = taskRepository.findFiltered(
                bypassTenantScope,
                tenantId,
                searchPattern != null,
                searchPattern,
                filter.getStatus() != null,
                filter.getStatus(),
                filter.getAvailable() != null,
                filter.getAvailable(),
                filter.getSource() != null,
                filter.getSource(),
                filter.getTier() != null,
                filter.getTier(),
                filter.getDeadlineFrom() != null,
                filter.getDeadlineFrom(),
                filter.getDeadlineTo() != null,
                filter.getDeadlineTo(),
                Boolean.TRUE.equals(filter.getNoDeadline()),
                filter.getUserId() != null,
                filter.getUserId(),
                sortedPageable);
        return PageResponseDto.from(tasks.map(taskRestMapper::toResponseDto));
    }

    // Sorting is a closed list, not "any Task property": every sortable column is a query the
    // database has to serve at scale, and an arbitrary one (title, description) means sorting
    // every matching row on each request. Adding a property here is a decision to support it -
    // check its plan with EXPLAIN first, and give it an index if it needs one.
    //
    // With no sort requested the list is newest first (served by idx_tasks_tenant_created_at_id).
    //
    // The id is always appended as the last sort key. Without a unique tie-breaker the order of
    // rows that share a value is undefined - every task with no deadline, every task of one bulk
    // import - and paging through an undefined order can repeat a task on two pages or skip one.
    // It takes the direction of the key before it, so an index on (that key, id) can serve the
    // whole order in either direction.
    private Pageable withSupportedSort(Pageable pageable) {
        if (pageable.isUnpaged()) {
            return pageable;
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order requested : pageable.getSort()) {
            String property = SORTABLE_PROPERTIES.get(requested.getProperty());
            if (property == null) {
                throw new InvalidTaskSortException(requested.getProperty(), SORTABLE_PROPERTIES.keySet());
            }
            orders.add(new Sort.Order(requested.getDirection(), property));
        }
        if (orders.isEmpty()) {
            orders.add(Sort.Order.desc("createdAt"));
        }
        orders.add(new Sort.Order(orders.get(orders.size() - 1).getDirection(), "id"));
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orders));
    }

    // Either end of the deadline range may be given alone (open-ended range); when both are given
    // deadlineTo must not be before deadlineFrom. noDeadline=true is mutually exclusive with the
    // range (asking for "no deadline at all" and "deadline within this range" in the same
    // request is self-contradictory).
    private void validateFilter(TaskFilterDto filter) {
        boolean hasFrom = filter.getDeadlineFrom() != null;
        boolean hasTo = filter.getDeadlineTo() != null;
        if (hasFrom && hasTo && filter.getDeadlineTo().isBefore(filter.getDeadlineFrom())) {
            throw new InvalidTaskFilterException("deadlineTo must not be before deadlineFrom");
        }
        if (Boolean.TRUE.equals(filter.getNoDeadline()) && (hasFrom || hasTo)) {
            throw new InvalidTaskFilterException("noDeadline cannot be combined with deadlineFrom/deadlineTo");
        }
    }

    private void validateStatusTransition(Task task, TaskStatus newStatus, Instant snoozedUntil) {
        if (newStatus == TaskStatus.SNOOZED && snoozedUntil == null) {
            throw new InvalidTaskStatusTransitionException("snoozedUntil is required when status is SNOOZED");
        }
        Set<TaskStatus> forbidden = FORBIDDEN_STATUS_TRANSITIONS.getOrDefault(task.getStatus(), Set.of());
        if (forbidden.contains(newStatus)) {
            throw new InvalidTaskStatusTransitionException(
                    "A task in status " + task.getStatus() + " cannot move to " + newStatus);
        }
    }

    @Transactional
    public TaskResponseDto updateTaskFromRequest(UUID id, TaskUpdateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Task task = findTaskOrThrow(id);
        taskAuthorization.assertWithinScope(taskAuthorization.scopeOf(actor), task);

        Project resolvedProject = request.getProjectId().isPresent()
                ? resolveProjectInTenant(request.getProjectId().get(), task.getTenant())
                : null;
        User resolvedOwner = request.getOwnerId().isPresent()
                ? resolveUserInTenant(request.getOwnerId().get(), task.getTenant())
                : null;

        Map<String, Object> before = auditSnapshot(task);
        taskRestMapper.applyUpdate(task, request, resolvedProject, resolvedOwner);
        Task saved = persistUpdatedTask(task, before, null, actor, "TASK_UPDATED");
        return taskRestMapper.toResponseDto(saved);
    }

    @Transactional
    public TaskResponseDto updateTaskStatus(UUID id, TaskStatusUpdateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Task task = findTaskOrThrow(id);
        taskAuthorization.assertWithinScope(taskAuthorization.scopeOf(actor), task);

        TaskStatus newStatus = TaskStatus.valueOf(request.getStatus());
        validateStatusTransition(task, newStatus, request.getSnoozedUntil());

        Map<String, Object> before = auditSnapshot(task);
        taskRestMapper.applyStatusChange(task, newStatus, request.getSnoozedUntil());
        Task saved = persistUpdatedTask(task, before, null, actor, "TASK_STATUS_UPDATED");
        return taskRestMapper.toResponseDto(saved);
    }

    // ---- Kafka ingestion / scheduling methods (TaskIngestionService, SchedulingConsumer) ----

    @Transactional
    public Task createTask(Task incoming, UUID correlationId) {
        return createTask(incoming, List.of(), correlationId);
    }

    @Transactional
    public Task createTask(Task incoming, List<Assignment> assignments, UUID correlationId) {
        Optional<Task> existing = findExisting(incoming);
        if (existing.isPresent()) {
            return existing.get();
        }

        return persistNewTask(incoming, assignments, correlationId, incoming.getCreatedBy());
    }

    private Task persistNewTask(Task task, List<Assignment> assignments, UUID correlationId, User actor) {
        Task saved = insertTask(task);

        for (Assignment assignment : assignments) {
            assignment.setTask(saved);
            assignmentRepository.save(assignment);
        }

        outboxEventRepository.save(buildOutboxEvent(EventType.TASK_CREATED, TaskCreatedPayload.from(saved), saved, correlationId));
        // Assignments are only ever written here, together with the task, so they are audited
        // as part of the creation row instead of one row each.
        Map<String, Object> snapshot = auditSnapshot(saved);
        snapshot.put("assignments", assignments.stream().map(this::assignmentSnapshot).toList());
        auditService.createAuditLog("TASK_CREATED", "Task", saved.getId(), saved.getTenant(), actor,
                snapshot, correlationId);
        return saved;
    }

    @Transactional
    public Task updateTask(UUID taskId, Task changes, UUID correlationId) {
        Task existing = findTaskOrThrow(taskId);

        Map<String, Object> before = auditSnapshot(existing);
        taskMapper.applyUpdate(existing, changes);

        return persistUpdatedTask(existing, before, correlationId, null, "TASK_UPDATED");
    }

    // Shared by the Kafka ingestion/scheduling path (updateTask above - no actor) and both REST
    // update paths (TaskController - plain update and status update, distinguished only by the
    // audit action label; both change a Task and publish the same TASK_UPDATED outbox event).
    // before is the audit snapshot the caller took while the task still held its old values -
    // every caller changes the Task object in place before getting here, so it cannot be
    // rebuilt at this point.
    private Task persistUpdatedTask(Task task, Map<String, Object> before, UUID correlationId, User actor,
                                    String auditAction) {
        Task saved = taskRepository.save(task);

        outboxEventRepository.save(buildOutboxEvent(EventType.TASK_UPDATED, TaskUpdatedPayload.from(saved), saved, correlationId));
        auditService.createUpdateAuditLog(auditAction, "Task", saved.getId(), saved.getTenant(), actor,
                before, auditSnapshot(saved), correlationId);
        return saved;
    }

    @Transactional
    public Task completeTask(UUID taskId, User completedBy, UUID correlationId) {
        Task existing = findTaskOrThrow(taskId);

        return completeTask(existing, completedBy, correlationId);
    }

    @Transactional
    public Task completeTask(Task task, User completedBy, UUID correlationId) {
        Map<String, Object> before = auditSnapshot(task);
        task.setStatus(TaskStatus.DONE);
        task.setCompletedAt(Instant.now(clock));
        Task saved = taskRepository.save(task);

        outboxEventRepository.save(buildOutboxEvent(EventType.TASK_COMPLETED, TaskCompletedPayload.from(saved), saved, correlationId));
        auditService.createUpdateAuditLog("TASK_COMPLETED", "Task", saved.getId(), saved.getTenant(), completedBy,
                before, auditSnapshot(saved), correlationId);
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<Task> findExisting(Task incoming) {
        return taskRepository.findByTenant_IdAndSourceTypeAndSourceRef(
                incoming.getTenant().getId(), incoming.getSourceType(), incoming.getSourceRef());
    }

    public Task applySchedulingUpdate(SchedulingEventDto event) {
        return applySchedulingUpdate(
                UUID.fromString(event.getTaskId()),
                event.getAvailable(),
                event.getPriorityScore() != null ? event.getPriorityScore().floatValue() : null,
                event.getEventId() != null ? UUID.fromString(event.getEventId()) : UUID.randomUUID());
    }

    @Transactional
    public Task applySchedulingUpdate(UUID taskId, boolean available, Float priorityScore, UUID correlationId) {
        Task task = findTaskOrThrow(taskId);
        // Taken before the setters below: they change this same Task object in place, so the
        // previous values are gone once they run.
        Map<String, Object> before = schedulingSnapshot(task);

        task.setAvailable(available);
        if (priorityScore != null) {
            task.setPriorityScore(priorityScore);
        }
        task.setUpdatedAt(Instant.now(clock));

        Task saved = taskRepository.saveAndFlush(task);

        outboxEventRepository.save(buildOutboxEvent(EventType.TASK_UPDATED, TaskUpdatedPayload.from(saved), saved, correlationId));
        // No actor: this is Chain's own calculation, not something a user did.
        auditService.createUpdateAuditLog("TASK_SCHEDULING_UPDATED", "Task", saved.getId(), saved.getTenant(),
                null, before, schedulingSnapshot(saved), correlationId);
        return saved;
    }

    private Task insertTask(Task incoming) {
        return taskRepository.saveAndFlush(incoming);
    }

    private Task findTaskOrThrow(UUID id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new TaskNotFoundException(id));
    }

    private Tenant findTenantOrThrow(UUID id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new TenantNotFoundException(id));
    }

    private User findActorOrThrow(UUID actorId) {
        return userRepository.findById(actorId)
                .orElseThrow(() -> new UnauthenticatedException("Actor " + actorId + " does not exist"));
    }

    // rawId == null means "no project" - returns null unconditionally in that case, whether
    // that's because create omitted it or update sent an explicit null to clear it.
    private Project resolveProjectInTenant(String rawId, Tenant tenant) {
        if (rawId == null) {
            return null;
        }
        UUID id = UUID.fromString(rawId);
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ProjectNotFoundException(id));
        if (!project.getTenant().getId().equals(tenant.getId())) {
            throw new ForbiddenActionException("Project " + id + " does not belong to tenant " + tenant.getId());
        }
        return project;
    }

    // Same null-passthrough convention as resolveProjectInTenant above.
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

    private OutboxEvent buildOutboxEvent(EventType eventType, Object payload, Task saved, UUID correlationId) {
        Map<String, Object> payloadMap = objectMapper.convertValue(payload, PAYLOAD_TYPE);

        return OutboxEvent.pending(
                eventType,
                saved.getId(),
                saved.getId().toString(),
                payloadMap,
                correlationId,
                Instant.now(clock));
    }

    private Map<String, Object> assignmentSnapshot(Assignment assignment) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("userId", assignment.getUser() != null ? assignment.getUser().getId() : null);
        snapshot.put("teamId", assignment.getTeam() != null ? assignment.getTeam().getId() : null);
        snapshot.put("assignmentRole", assignment.getAssignmentRole().name());
        snapshot.put("assignedById",
                assignment.getAssignedBy() != null ? assignment.getAssignedBy().getId() : null);
        return snapshot;
    }

    private Map<String, Object> schedulingSnapshot(Task task) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("isAvailable", task.isAvailable());
        snapshot.put("priorityScore", task.getPriorityScore());
        return snapshot;
    }

    // Every field an update path can change is here (plus sourceType/sourceRef, which identify
    // the task). createUpdateAuditLog writes no row when the snapshot is unchanged, so a field
    // left out of it would make an update of only that field go unaudited.
    private Map<String, Object> auditSnapshot(Task task) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", task.getTitle());
        snapshot.put("description", task.getDescription());
        snapshot.put("sourceType", task.getSourceType().name());
        snapshot.put("sourceRef", task.getSourceRef());
        snapshot.put("status", task.getStatus().name());
        snapshot.put("tier", task.getTier() != null ? task.getTier().name() : null);
        snapshot.put("roleContext", task.getRoleContext() != null ? task.getRoleContext().name() : null);
        snapshot.put("urgency", task.getUrgency());
        snapshot.put("effortEstimateMinutes", task.getEffortEstimateMinutes());
        snapshot.put("effortEstimateConfidence", task.getEffortEstimateConfidence());
        snapshot.put("deadline", task.getDeadline());
        snapshot.put("snoozedUntil", task.getSnoozedUntil());
        snapshot.put("completedAt", task.getCompletedAt());
        snapshot.put("projectId", task.getProject() != null ? task.getProject().getId() : null);
        snapshot.put("ownerId", task.getOwner() != null ? task.getOwner().getId() : null);
        snapshot.put("suggestedOwnerUserId",
                task.getSuggestedOwnerUser() != null ? task.getSuggestedOwnerUser().getId() : null);
        snapshot.put("suggestedOwnerConfidence", task.getSuggestedOwnerConfidence());
        return snapshot;
    }
}
