package com.rnd.inbox.task.service;
import com.rnd.inbox.task.assignment.Assignment;
import com.rnd.inbox.task.assignment.AssignmentRepository;
import com.rnd.inbox.task.assignment.AssignmentRole;
import com.rnd.inbox.task.dto.TaskCreateRequestDto;
import com.rnd.inbox.task.dto.TaskFilterDto;
import com.rnd.inbox.task.dto.TaskResponseDto;
import com.rnd.inbox.task.dto.TaskStatusUpdateRequestDto;
import com.rnd.inbox.task.dto.TaskUpdateRequestDto;
import com.rnd.inbox.task.exception.InvalidTaskFilterException;
import com.rnd.inbox.task.exception.InvalidTaskStatusTransitionException;
import com.rnd.inbox.task.exception.TaskNotFoundException;
import com.rnd.inbox.task.mapper.TaskMapper;
import com.rnd.inbox.task.mapper.TaskRestMapper;
import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.model.TaskExtractionProvenance;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.repository.TaskRepository;
import com.rnd.inbox.task.security.TaskAuthorization;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.inbox.audit.AuditService;
import com.rnd.inbox.outbox.OutboxEvent;
import com.rnd.inbox.outbox.OutboxEventRepository;
import com.rnd.inbox.outbox.OutboxStatus;
import com.rnd.inbox.project.Project;
import com.rnd.inbox.project.ProjectRepository;
import com.rnd.inbox.scheduling.SchedulingEventDto;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.event.EventType;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.tenant.repository.TenantRepository;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.repository.UserRepository;
import com.rnd.inbox.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openapitools.jackson.nullable.JsonNullable;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    private static final String SOURCE_REF = "github:owner/repo:issue:42";
    private static final Instant NOW = Instant.parse("2026-08-31T10:15:30Z");

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private AssignmentRepository assignmentRepository;

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TaskRestMapper taskRestMapper;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private TaskService taskService;
    private Tenant tenant;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(
                taskRepository,
                auditService,
                outboxEventRepository,
                assignmentRepository,
                taskMapper,
                objectMapper,
                clock,
                tenantRepository,
                projectRepository,
                userRepository,
                new TaskAuthorization(),
                taskRestMapper);
        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);
    }

    @Test
    void createTask_whenNoTaskExistsYet_persistsTaskAndWritesAuditLog() {
        Task incoming = incomingTask();
        UUID correlationId = UUID.randomUUID();
        UUID generatedId = UUID.randomUUID();
        stubNoExistingTask();
        stubSaveAssigning(incoming, generatedId);

        Task result = taskService.createTask(incoming, correlationId);

        assertThat(result.getId()).isEqualTo(generatedId);
        verify(taskRepository).saveAndFlush(incoming);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> snapshotCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).createAuditLog(eq("TASK_CREATED"), eq("Task"), eq(generatedId), eq(tenant), eq(incoming.getCreatedBy()),
                snapshotCaptor.capture(), eq(correlationId));
        assertThat(snapshotCaptor.getValue())
                .containsEntry("title", "Fix flaky test")
                .containsEntry("sourceType", "GITHUB_ISSUE")
                .containsEntry("sourceRef", SOURCE_REF)
                .containsEntry("status", "NEW");
    }

    @Test
    void createTask_whenNoTaskExistsYet_writesPendingOutboxEventKeyedByTheSavedTaskId() {
        Task incoming = incomingTask();
        UUID correlationId = UUID.randomUUID();
        UUID generatedId = UUID.randomUUID();
        stubNoExistingTask();
        stubSaveAssigning(incoming, generatedId);

        taskService.createTask(incoming, correlationId);

        OutboxEvent event = capturedOutboxEvent();
        assertThat(event.getEventType()).isEqualTo(EventType.TASK_CREATED);
        assertThat(event.getAggregateId()).isEqualTo(generatedId);
        assertThat(event.getMessageKey()).isEqualTo(generatedId.toString());
        assertThat(event.getCorrelationId()).isEqualTo(correlationId);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getCreatedAt()).isEqualTo(NOW);
        assertThat(event.getNextRetryAt()).isEqualTo(NOW);
    }

    @Test
    void createTask_whenNoTaskExistsYet_buildsOutboxPayloadFromTheSavedTask() {
        Task incoming = incomingTask();
        UUID generatedId = UUID.randomUUID();
        stubNoExistingTask();
        stubSaveAssigning(incoming, generatedId);

        taskService.createTask(incoming, UUID.randomUUID());

        assertThat(capturedOutboxEvent().getPayload())
                .containsEntry("taskId", generatedId.toString())
                .containsEntry("tenantId", tenantId.toString())
                .containsEntry("title", "Fix flaky test")
                .containsEntry("sourceType", "GITHUB_ISSUE")
                .containsEntry("sourceRef", SOURCE_REF)
                .containsEntry("status", "NEW");
    }

    @Test
    void createTask_whenCorrelationIdIsNull_writesOutboxEventWithoutCorrelationId() {
        Task incoming = incomingTask();
        stubNoExistingTask();
        stubSaveAssigning(incoming, UUID.randomUUID());

        taskService.createTask(incoming, null);

        assertThat(capturedOutboxEvent().getCorrelationId()).isNull();
    }

    @Test
    void createTask_whenTaskAlreadyExists_returnsExistingWithoutPersistingOrEnqueueing() {
        Task incoming = incomingTask();
        Task existing = incomingTask();
        existing.setId(UUID.randomUUID());
        when(taskRepository.findByTenant_IdAndSourceTypeAndSourceRef(
                tenantId, SourceType.GITHUB_ISSUE, SOURCE_REF))
                .thenReturn(Optional.of(existing));

        Task result = taskService.createTask(incoming, UUID.randomUUID());

        assertThat(result).isSameAs(existing);
        verify(taskRepository, never()).saveAndFlush(any());
        verify(outboxEventRepository, never()).save(any());
        verify(auditService, never()).createAuditLog(any(), any(), any(), any(), any(), any());
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void createTask_withAssignment_savesItLinkedToTheSavedTask() {
        Task incoming = incomingTask();
        UUID generatedId = UUID.randomUUID();
        stubNoExistingTask();
        stubSaveAssigning(incoming, generatedId);
        Assignment assignment = new Assignment();
        assignment.setAssignmentRole(AssignmentRole.ASSIGNEE);

        Task result = taskService.createTask(incoming, List.of(assignment), UUID.randomUUID());

        ArgumentCaptor<Assignment> captor = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(assignment);
        assertThat(captor.getValue().getTask()).isSameAs(result);
    }

    @Test
    void createTask_withMultipleAssignments_savesEachOneLinkedToTheSavedTask() {
        Task incoming = incomingTask();
        UUID generatedId = UUID.randomUUID();
        stubNoExistingTask();
        stubSaveAssigning(incoming, generatedId);
        Assignment owner = new Assignment();
        owner.setAssignmentRole(AssignmentRole.OWNER);
        Assignment collaborator = new Assignment();
        collaborator.setAssignmentRole(AssignmentRole.COLLABORATOR);

        Task result = taskService.createTask(incoming, List.of(owner, collaborator), UUID.randomUUID());

        ArgumentCaptor<Assignment> captor = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).containsExactly(owner, collaborator);
        assertThat(captor.getAllValues()).allSatisfy(a -> assertThat(a.getTask()).isSameAs(result));
    }

    @Test
    void createTask_withoutAssignments_neverTouchesTheAssignmentRepository() {
        Task incoming = incomingTask();
        stubNoExistingTask();
        stubSaveAssigning(incoming, UUID.randomUUID());

        taskService.createTask(incoming, List.of(), UUID.randomUUID());

        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void updateTask_persistsChangesAndWritesAuditLog() {
        Task task = incomingTask();
        task.setId(UUID.randomUUID());
        UUID correlationId = UUID.randomUUID();
        when(taskRepository.save(task)).thenReturn(task);

        Task result = taskService.updateTask(task, correlationId);

        assertThat(result).isSameAs(task);
        verify(taskRepository).save(task);
        verify(auditService).createAuditLog(eq("TASK_UPDATED"), eq("Task"), eq(task.getId()), eq(tenant),
                isNull(), any(), eq(correlationId));
    }

    @Test
    void updateTask_writesPendingOutboxEventWithTaskUpdatedType() {
        Task task = incomingTask();
        task.setId(UUID.randomUUID());
        when(taskRepository.save(task)).thenReturn(task);

        taskService.updateTask(task, UUID.randomUUID());

        OutboxEvent event = capturedOutboxEvent();
        assertThat(event.getEventType()).isEqualTo(EventType.TASK_UPDATED);
        assertThat(event.getAggregateId()).isEqualTo(task.getId());
        assertThat(event.getPayload()).containsEntry("taskId", task.getId().toString());
    }

    @Test
    void updateTask_whenTaskIdFound_appliesMapperAndDelegatesToInternalUpdate() {
        UUID taskId = UUID.randomUUID();
        Task existing = incomingTask();
        existing.setId(taskId);
        Task changes = new Task();
        changes.setTitle("New title");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(existing));
        when(taskRepository.save(existing)).thenReturn(existing);

        Task result = taskService.updateTask(taskId, changes, UUID.randomUUID());

        verify(taskMapper).applyUpdate(existing, changes);
        assertThat(result).isSameAs(existing);
    }

    @Test
    void updateTask_whenTaskIdNotFound_throwsTaskNotFoundExceptionAndNeverTouchesOutboxOrAudit() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.updateTask(taskId, new Task(), UUID.randomUUID()))
                .isInstanceOf(TaskNotFoundException.class);

        verify(outboxEventRepository, never()).save(any());
        verify(auditService, never()).createAuditLog(any(), any(), any(), any(), any(), any());
    }

    @Test
    void completeTask_setsStatusDoneAndCompletedAt() {
        Task task = incomingTask();
        task.setId(UUID.randomUUID());
        when(taskRepository.save(task)).thenReturn(task);

        Task result = taskService.completeTask(task, null, UUID.randomUUID());

        assertThat(result.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(result.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void completeTask_writesPendingOutboxEventWithTaskCompletedTypeAndIsCompletedTrue() {
        Task task = incomingTask();
        task.setId(UUID.randomUUID());
        when(taskRepository.save(task)).thenReturn(task);

        taskService.completeTask(task, null, UUID.randomUUID());

        OutboxEvent event = capturedOutboxEvent();
        assertThat(event.getEventType()).isEqualTo(EventType.TASK_COMPLETED);
        assertThat(event.getPayload())
                .containsEntry("taskId", task.getId().toString())
                .containsEntry("completed", true);
    }

    @Test
    void completeTask_writesAuditLogWithActor() {
        Task task = incomingTask();
        task.setId(UUID.randomUUID());
        User completedBy = new User();
        completedBy.setId(UUID.randomUUID());
        when(taskRepository.save(task)).thenReturn(task);

        taskService.completeTask(task, completedBy, UUID.randomUUID());

        verify(auditService).createAuditLog(eq("TASK_COMPLETED"), eq("Task"), eq(task.getId()), eq(tenant),
                eq(completedBy), any(), any());
    }

    @Test
    void completeTask_whenTaskIdFound_delegatesToInternalComplete() {
        UUID taskId = UUID.randomUUID();
        Task existing = incomingTask();
        existing.setId(taskId);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(existing));
        when(taskRepository.save(existing)).thenReturn(existing);

        Task result = taskService.completeTask(taskId, null, UUID.randomUUID());

        assertThat(result.getStatus()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void completeTask_whenTaskIdNotFound_throwsTaskNotFoundExceptionAndNeverTouchesOutbox() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.completeTask(taskId, null, UUID.randomUUID()))
                .isInstanceOf(TaskNotFoundException.class);

        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void applySchedulingUpdate_fromASchedulingEvent_materializesBothValuesOnTheTask() {
        UUID taskId = UUID.randomUUID();
        Task task = incomingTask();
        task.setId(taskId);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.saveAndFlush(task)).thenReturn(task);

        UUID eventId = UUID.randomUUID();
        Task result = taskService.applySchedulingUpdate(schedulingEvent(taskId, eventId, 7.5));

        assertThat(result.isAvailable()).isTrue();
        assertThat(result.getPriorityScore()).isEqualTo(7.5f);
        assertThat(capturedOutboxEvent().getCorrelationId()).isEqualTo(eventId);
    }

    @Test
    void applySchedulingUpdate_fromASchedulingEventWithoutAScore_leavesTheStoredScoreAlone() {
        UUID taskId = UUID.randomUUID();
        Task task = incomingTask();
        task.setId(taskId);
        task.setPriorityScore(4.0f);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.saveAndFlush(task)).thenReturn(task);

        Task result = taskService.applySchedulingUpdate(schedulingEvent(taskId, UUID.randomUUID(), null));

        assertThat(result.getPriorityScore()).isEqualTo(4.0f);
    }

    @Test
    void applySchedulingUpdate_forATaskThatDoesNotExist_throwsTaskNotFoundExceptionRatherThanFailingOnSave() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.applySchedulingUpdate(
                schedulingEvent(taskId, UUID.randomUUID(), 5.0)))
                .isInstanceOf(TaskNotFoundException.class);

        verify(outboxEventRepository, never()).save(any());
    }

    private SchedulingEventDto schedulingEvent(UUID taskId, UUID eventId, Double priorityScore) {
        SchedulingEventDto event = new SchedulingEventDto();
        event.setEventId(eventId.toString());
        event.setTaskId(taskId.toString());
        event.setAvailable(true);
        event.setPriorityScore(priorityScore);
        return event;
    }

    private void stubNoExistingTask() {
        when(taskRepository.findByTenant_IdAndSourceTypeAndSourceRef(
                tenantId, SourceType.GITHUB_ISSUE, SOURCE_REF))
                .thenReturn(Optional.empty());
    }

    private void stubSaveAssigning(Task incoming, UUID generatedId) {
        when(taskRepository.saveAndFlush(incoming)).thenAnswer(invocation -> {
            incoming.setId(generatedId);
            return incoming;
        });
    }

    private OutboxEvent capturedOutboxEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        return captor.getValue();
    }

    private Task incomingTask() {
        Project project = new Project();
        project.setId(UUID.randomUUID());

        User createdBy = new User();
        createdBy.setId(UUID.randomUUID());

        Task task = new Task();
        task.setTenant(tenant);
        task.setProject(project);
        task.setTitle("Fix flaky test");
        task.setDescription("Investigate the intermittent failure");
        task.setSourceType(SourceType.GITHUB_ISSUE);
        task.setSourceRef(SOURCE_REF);
        task.setStatus(TaskStatus.NEW);
        task.setExtractionProvenance(TaskExtractionProvenance.ETL);
        task.setCreatedBy(createdBy);
        task.setCreatedAt(NOW);
        return task;
    }

    // ---- REST-facing methods ----

    // TaskResponseDto is final - constructed for real rather than mocked, per this codebase's
    // convention (mocking a final DTO depends on a Mockito setting that isn't a safe assumption).
    private TaskResponseDto sampleTaskResponseDto() {
        return new TaskResponseDto(UUID.randomUUID(), null, null, "Fix flaky test", null, null, null, null, null,
                null, null, null, TaskStatus.NEW, false, null, null, null, null, null, null, null, null, null,
                NOW, NOW, null, null);
    }

    private User actorWithRole(UserRole role) {
        User actor = new User();
        actor.setId(UUID.randomUUID());
        actor.setTenant(tenant);
        actor.setRole(role);
        return actor;
    }

    @Test
    void createTaskFromRequest_whenOwnerOmitted_defaultsOwnerToActor() {
        User actor = actorWithRole(UserRole.EMPLOYEE);
        when(userRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        TaskCreateRequestDto request = new TaskCreateRequestDto();
        request.setTenantId(tenantId.toString());
        request.setTitle("Fix flaky test");

        Task built = new Task();
        built.setTenant(tenant);
        built.setSourceType(SourceType.MANUAL);
        built.setStatus(TaskStatus.NEW);
        when(taskRestMapper.toEntity(request, tenant, null, actor, actor)).thenReturn(built);
        stubSaveAssigning(built, UUID.randomUUID());
        TaskResponseDto responseDto = new TaskResponseDto(UUID.randomUUID(), null, null, "Fix flaky test",
                null, null, null, null, null, null, null, null, TaskStatus.NEW, false, null, null, null, null,
                actor.getId(), null, null, null, actor.getId(), NOW, NOW, null, null);
        when(taskRestMapper.toResponseDto(built)).thenReturn(responseDto);

        TaskResponseDto result = taskService.createTaskFromRequest(request, actor.getId());

        assertThat(result).isSameAs(responseDto);
        verify(auditService).createAuditLog(eq("TASK_CREATED"), eq("Task"), any(), eq(tenant),
                eq(actor), any(), isNull());
    }

    @Test
    void createTaskFromRequest_whenActorDoesNotExist_throwsUnauthenticatedException() {
        UUID actorId = UUID.randomUUID();
        when(userRepository.findById(actorId)).thenReturn(Optional.empty());

        TaskCreateRequestDto request = new TaskCreateRequestDto();
        request.setTenantId(tenantId.toString());
        request.setTitle("Fix flaky test");

        assertThatThrownBy(() -> taskService.createTaskFromRequest(request, actorId))
                .isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void createTaskFromRequest_whenActorIsTenantAdminOfAnotherTenant_throwsForbiddenActionException() {
        User actor = actorWithRole(UserRole.TENANT_ADMIN);
        actor.setTenant(new Tenant());
        actor.getTenant().setId(UUID.randomUUID());
        when(userRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        TaskCreateRequestDto request = new TaskCreateRequestDto();
        request.setTenantId(tenantId.toString());
        request.setTitle("Fix flaky test");

        assertThatThrownBy(() -> taskService.createTaskFromRequest(request, actor.getId()))
                .isInstanceOf(ForbiddenActionException.class);

        verify(taskRepository, never()).saveAndFlush(any());
    }

    @Test
    void getTaskById_whenActorIsSystemAdmin_returnsAnyTask() {
        User actor = actorWithRole(UserRole.SYSTEM_ADMIN);
        when(userRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
        Task task = new Task();
        task.setTenant(tenant);
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        TaskResponseDto responseDto = sampleTaskResponseDto();
        when(taskRestMapper.toResponseDto(task)).thenReturn(responseDto);

        TaskResponseDto result = taskService.getTaskById(taskId, actor.getId());

        assertThat(result).isSameAs(responseDto);
    }

    @Test
    void getTaskById_whenActorIsEmployeeOfAnotherTenant_throwsForbiddenActionException() {
        User actor = actorWithRole(UserRole.EMPLOYEE);
        actor.setTenant(new Tenant());
        actor.getTenant().setId(UUID.randomUUID());
        when(userRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
        Task task = new Task();
        task.setTenant(tenant);
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.getTaskById(taskId, actor.getId()))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void getTaskById_whenTaskNotFound_throwsTaskNotFoundException() {
        User actor = actorWithRole(UserRole.SYSTEM_ADMIN);
        when(userRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.getTaskById(taskId, actor.getId()))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void listTasks_whenActorIsSystemAdmin_passesNullTenantId() {
        User actor = actorWithRole(UserRole.SYSTEM_ADMIN);
        when(userRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
        Pageable pageable = PageRequest.of(0, 20);
        Task task = new Task();
        Page<Task> page = new PageImpl<>(List.of(task), pageable, 1);
        when(taskRepository.findFiltered(
                eq(true), isNull(), eq(false), isNull(), eq(false), isNull(), eq(false), isNull(), eq(false),
                isNull(), eq(false), isNull(), eq(false), isNull(), eq(false), isNull(), eq(false),
                eq(false), isNull(), eq(pageable)))
                .thenReturn(page);
        TaskResponseDto responseDto = sampleTaskResponseDto();
        when(taskRestMapper.toResponseDto(task)).thenReturn(responseDto);

        PageResponseDto<TaskResponseDto> result = taskService.listTasks(new TaskFilterDto(), pageable, actor.getId());

        assertThat(result.getContent()).containsExactly(responseDto);
    }

    @Test
    void listTasks_whenActorIsTenantAdmin_passesOwnTenantId() {
        User actor = actorWithRole(UserRole.TENANT_ADMIN);
        when(userRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
        Pageable pageable = PageRequest.of(0, 20);
        Task task = new Task();
        Page<Task> page = new PageImpl<>(List.of(task), pageable, 1);
        when(taskRepository.findFiltered(
                eq(false), eq(tenantId), eq(false), isNull(), eq(false), isNull(), eq(false), isNull(), eq(false),
                isNull(), eq(false), isNull(), eq(false), isNull(), eq(false), isNull(), eq(false),
                eq(false), isNull(), eq(pageable)))
                .thenReturn(page);
        TaskResponseDto responseDto = sampleTaskResponseDto();
        when(taskRestMapper.toResponseDto(task)).thenReturn(responseDto);

        PageResponseDto<TaskResponseDto> result = taskService.listTasks(new TaskFilterDto(), pageable, actor.getId());

        assertThat(result.getContent()).containsExactly(responseDto);
    }

    @Test
    void listTasks_whenUserIdProvided_passesItAlongsideTheActorsOwnTenantScope() {
        User actor = actorWithRole(UserRole.TENANT_ADMIN);
        when(userRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
        Pageable pageable = PageRequest.of(0, 20);
        Task task = new Task();
        Page<Task> page = new PageImpl<>(List.of(task), pageable, 1);
        UUID relatedUserId = UUID.randomUUID();
        // The tenant scope (bypass=false, tenantId) must still be passed - userId narrows the
        // result inside the actor's scope, it never replaces it.
        when(taskRepository.findFiltered(
                eq(false), eq(tenantId), eq(false), isNull(), eq(false), isNull(), eq(false), isNull(), eq(false),
                isNull(), eq(false), isNull(), eq(false), isNull(), eq(false), isNull(), eq(false),
                eq(true), eq(relatedUserId), eq(pageable)))
                .thenReturn(page);
        TaskResponseDto responseDto = sampleTaskResponseDto();
        when(taskRestMapper.toResponseDto(task)).thenReturn(responseDto);
        TaskFilterDto filter = new TaskFilterDto();
        filter.setUserId(relatedUserId);

        PageResponseDto<TaskResponseDto> result = taskService.listTasks(filter, pageable, actor.getId());

        assertThat(result.getContent()).containsExactly(responseDto);
    }

    @Test
    void listTasks_whenOnlyDeadlineFromProvided_throwsInvalidTaskFilterException() {
        User actor = actorWithRole(UserRole.TENANT_ADMIN);
        TaskFilterDto filter = new TaskFilterDto();
        filter.setDeadlineFrom(Instant.now());

        assertThatThrownBy(() -> taskService.listTasks(filter, PageRequest.of(0, 20), actor.getId()))
                .isInstanceOf(InvalidTaskFilterException.class);
        verifyNoInteractions(taskRepository);
    }

    @Test
    void listTasks_whenNoDeadlineCombinedWithDeadlineRange_throwsInvalidTaskFilterException() {
        User actor = actorWithRole(UserRole.TENANT_ADMIN);
        TaskFilterDto filter = new TaskFilterDto();
        filter.setDeadlineFrom(Instant.now());
        filter.setDeadlineTo(Instant.now());
        filter.setNoDeadline(true);

        assertThatThrownBy(() -> taskService.listTasks(filter, PageRequest.of(0, 20), actor.getId()))
                .isInstanceOf(InvalidTaskFilterException.class);
        verifyNoInteractions(taskRepository);
    }

    @Test
    void updateTaskFromRequest_whenActorIsEmployeeOfAnotherTenant_throwsForbiddenActionException() {
        User actor = actorWithRole(UserRole.EMPLOYEE);
        actor.setTenant(new Tenant());
        actor.getTenant().setId(UUID.randomUUID());
        when(userRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
        Task task = new Task();
        task.setTenant(tenant);
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        TaskUpdateRequestDto request = new TaskUpdateRequestDto();
        request.setTitle("New title");

        assertThatThrownBy(() -> taskService.updateTaskFromRequest(taskId, request, actor.getId()))
                .isInstanceOf(ForbiddenActionException.class);

        verify(taskRepository, never()).save(any());
    }

    @Test
    void updateTaskFromRequest_whenTaskNotFound_throwsTaskNotFoundException() {
        User actor = actorWithRole(UserRole.SYSTEM_ADMIN);
        when(userRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        TaskUpdateRequestDto request = new TaskUpdateRequestDto();
        request.setTitle("New title");

        assertThatThrownBy(() -> taskService.updateTaskFromRequest(taskId, request, actor.getId()))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void updateTaskStatus_toSnoozedWithoutSnoozedUntil_throwsInvalidTaskStatusTransitionException() {
        User actor = actorWithRole(UserRole.SYSTEM_ADMIN);
        when(userRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
        Task task = new Task();
        task.setTenant(tenant);
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        TaskStatusUpdateRequestDto request = new TaskStatusUpdateRequestDto();
        request.setStatus("SNOOZED");

        assertThatThrownBy(() -> taskService.updateTaskStatus(taskId, request, actor.getId()))
                .isInstanceOf(InvalidTaskStatusTransitionException.class);

        verify(taskRepository, never()).save(any());
    }

    @Test
    void updateTaskStatus_whenTaskNotFound_throwsTaskNotFoundException() {
        User actor = actorWithRole(UserRole.SYSTEM_ADMIN);
        when(userRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        TaskStatusUpdateRequestDto request = new TaskStatusUpdateRequestDto();
        request.setStatus("DONE");

        assertThatThrownBy(() -> taskService.updateTaskStatus(taskId, request, actor.getId()))
                .isInstanceOf(TaskNotFoundException.class);
    }
}
