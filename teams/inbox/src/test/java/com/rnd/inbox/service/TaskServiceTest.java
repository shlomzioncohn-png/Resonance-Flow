package com.rnd.inbox.service;

import com.rnd.inbox.dto.TaskCreatedPayload;
import com.rnd.inbox.event.Event;
import com.rnd.inbox.event.EventPublisher;
import com.rnd.inbox.event.EventType;
import com.rnd.inbox.model.entity.AuditLog;
import com.rnd.inbox.model.entity.Organization;
import com.rnd.inbox.model.entity.Project;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.model.entity.User;
import com.rnd.inbox.model.enums.SourceType;
import com.rnd.inbox.model.enums.TaskExtractionProvenance;
import com.rnd.inbox.model.enums.TaskStatus;
import com.rnd.inbox.repository.AuditLogRepository;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String SOURCE_SERVICE = "inbox";

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private EventPublisher eventPublisher;

    private TaskService taskService;
    private Organization organization;
    private UUID organizationId;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(
                taskRepository,
                auditLogRepository,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
                eventPublisher,
                SOURCE_SERVICE);
        organizationId = UUID.randomUUID();
        organization = new Organization();
        organization.setId(organizationId);
    }

    private Task incomingTask() {
        Task task = new Task();
        task.setOrganization(organization);
        task.setTitle("Fix flaky test");
        task.setSourceType(SourceType.GITHUB_ISSUE);
        task.setSourceRef("github:owner/repo:issue:42");
        task.setStatus(TaskStatus.NEW);
        task.setExtractionProvenance(TaskExtractionProvenance.ETL);
        return task;
    }

    @Test
    void createsTaskAndAuditWhenNoneExistsYet() {
        Task incoming = incomingTask();
        UUID correlationId = UUID.randomUUID();
        UUID generatedId = UUID.randomUUID();

        when(taskRepository.findByOrganization_IdAndSourceTypeAndSourceRef(
                organizationId, SourceType.GITHUB_ISSUE, "github:owner/repo:issue:42"))
                .thenReturn(Optional.empty());
        when(taskRepository.saveAndFlush(incoming)).thenAnswer(invocation -> {
            incoming.setId(generatedId);
            return incoming;
        });

        Task result = taskService.createFromBridgeEvent(incoming, correlationId);

        assertThat(result.getId()).isEqualTo(generatedId);
        verify(taskRepository).saveAndFlush(incoming);

        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        AuditLog audit = auditCaptor.getValue();
        assertThat(audit.getAction()).isEqualTo("TASK_CREATED");
        assertThat(audit.getEntityType()).isEqualTo("Task");
        assertThat(audit.getEntityId()).isEqualTo(generatedId);
        assertThat(audit.getCorrelationId()).isEqualTo(correlationId);
        assertThat(audit.getCreatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void doesNotCreateAnotherTaskWhenOneAlreadyExists() {
        Task incoming = incomingTask();
        Task existing = incomingTask();
        existing.setId(UUID.randomUUID());

        when(taskRepository.findByOrganization_IdAndSourceTypeAndSourceRef(
                organizationId, SourceType.GITHUB_ISSUE, "github:owner/repo:issue:42"))
                .thenReturn(Optional.of(existing));

        Task result = taskService.createFromBridgeEvent(incoming, UUID.randomUUID());

        assertThat(result).isSameAs(existing);
        verify(taskRepository, never()).saveAndFlush(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void createTask_publishesTaskCreatedEvent_keyedByTheSavedTaskId() {
        Task saved = sampleTask();
        when(taskRepository.save(any(Task.class))).thenReturn(saved);
        when(eventPublisher.publish(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        taskService.createTask(new Task(), null);

        verify(eventPublisher).publish(eq(EventType.TASK_CREATED.getTopic()), eq(saved.getId().toString()), any());
    }

    @Test
    void createTask_buildsPayloadFromTheSavedTask() {
        Task saved = sampleTask();
        when(taskRepository.save(any(Task.class))).thenReturn(saved);
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        when(eventPublisher.publish(anyString(), anyString(), eventCaptor.capture()))
                .thenReturn(CompletableFuture.completedFuture(null));

        taskService.createTask(new Task(), null);

        @SuppressWarnings("unchecked")
        Event<TaskCreatedPayload> published = eventCaptor.getValue();
        TaskCreatedPayload payload = published.getPayload();
        assertThat(payload.getTaskId()).isEqualTo(saved.getId());
        assertThat(payload.getOrganizationId()).isEqualTo(saved.getOrganization().getId());
        assertThat(payload.getProjectId()).isEqualTo(saved.getProject().getId());
        assertThat(payload.getTitle()).isEqualTo(saved.getTitle());
        assertThat(payload.getStatus()).isEqualTo(saved.getStatus());
        assertThat(payload.getCreatedBy()).isEqualTo(saved.getCreatedBy().getId());
        assertThat(payload.getCreatedAt()).isEqualTo(saved.getCreatedAt());
    }

    @Test
    void createTask_bridgeFlow_publishesEventWithoutCorrelationId() {
        Task saved = sampleTask();
        when(taskRepository.save(any(Task.class))).thenReturn(saved);
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        when(eventPublisher.publish(anyString(), anyString(), eventCaptor.capture()))
                .thenReturn(CompletableFuture.completedFuture(null));

        taskService.createTask(new Task(), null);

        assertThat(eventCaptor.getValue().getCorrelationId()).isNull();
    }

    @Test
    void createTask_mindFlow_propagatesTheOriginalCorrelationId() {
        Task saved = sampleTask();
        UUID correlationId = UUID.randomUUID();
        when(taskRepository.save(any(Task.class))).thenReturn(saved);
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        when(eventPublisher.publish(anyString(), anyString(), eventCaptor.capture()))
                .thenReturn(CompletableFuture.completedFuture(null));

        taskService.createTask(new Task(), correlationId);

        assertThat(eventCaptor.getValue().getCorrelationId()).isEqualTo(correlationId);
    }

    @Test
    void createTask_returnsTheTaskPersistedByTheRepository() {
        Task saved = sampleTask();
        when(taskRepository.save(any(Task.class))).thenReturn(saved);
        when(eventPublisher.publish(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        Task result = taskService.createTask(new Task(), null);

        assertThat(result).isSameAs(saved);
    }

    private static Task sampleTask() {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());

        Project project = new Project();
        project.setId(UUID.randomUUID());

        User createdBy = new User();
        createdBy.setId(UUID.randomUUID());

        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setOrganization(organization);
        task.setProject(project);
        task.setTitle("Write the quarterly report");
        task.setDescription("Summarize Q3 results");
        task.setSourceType(SourceType.MANUAL);
        task.setStatus(TaskStatus.NEW);
        task.setExtractionProvenance(TaskExtractionProvenance.MANUAL);
        task.setCreatedBy(createdBy);
        task.setCreatedAt(Instant.now());
        return task;
    }
}
