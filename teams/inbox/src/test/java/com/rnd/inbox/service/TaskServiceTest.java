package com.rnd.inbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.inbox.event.EventType;
import com.rnd.inbox.exception.TaskNotFoundException;
import com.rnd.inbox.mapper.TaskMapper;
import com.rnd.inbox.model.entity.Assignment;
import com.rnd.inbox.model.entity.Organization;
import com.rnd.inbox.model.entity.OutboxEvent;
import com.rnd.inbox.model.entity.Project;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.model.entity.User;
import com.rnd.inbox.model.enums.AssignmentRole;
import com.rnd.inbox.model.enums.OutboxStatus;
import com.rnd.inbox.model.enums.SourceType;
import com.rnd.inbox.model.enums.TaskExtractionProvenance;
import com.rnd.inbox.model.enums.TaskStatus;
import com.rnd.inbox.repository.AssignmentRepository;
import com.rnd.inbox.repository.OutboxEventRepository;
import com.rnd.inbox.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private TaskService taskService;
    private Organization organization;
    private UUID organizationId;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(
                taskRepository,
                auditService,
                outboxEventRepository,
                assignmentRepository,
                taskMapper,
                objectMapper,
                clock);
        organizationId = UUID.randomUUID();
        organization = new Organization();
        organization.setId(organizationId);
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
        verify(auditService).createAuditLog(eq("TASK_CREATED"), eq("Task"), eq(generatedId), eq(organization), eq(incoming.getCreatedBy()),
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
                .containsEntry("organizationId", organizationId.toString())
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
        when(taskRepository.findByOrganization_IdAndSourceTypeAndSourceRef(
                organizationId, SourceType.GITHUB_ISSUE, SOURCE_REF))
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

        Task result = taskService.createTask(incoming, assignment, UUID.randomUUID());

        ArgumentCaptor<Assignment> captor = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(assignment);
        assertThat(captor.getValue().getTask()).isSameAs(result);
    }

    @Test
    void createTask_withoutAssignment_neverTouchesTheAssignmentRepository() {
        Task incoming = incomingTask();
        stubNoExistingTask();
        stubSaveAssigning(incoming, UUID.randomUUID());

        taskService.createTask(incoming, null, UUID.randomUUID());

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
        verify(auditService).createAuditLog(eq("TASK_UPDATED"), eq("Task"), eq(task.getId()), eq(organization),
                any(), eq(correlationId));
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

        verify(auditService).createAuditLog(eq("TASK_COMPLETED"), eq("Task"), eq(task.getId()), eq(organization),
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

    private void stubNoExistingTask() {
        when(taskRepository.findByOrganization_IdAndSourceTypeAndSourceRef(
                organizationId, SourceType.GITHUB_ISSUE, SOURCE_REF))
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
        task.setOrganization(organization);
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
}
