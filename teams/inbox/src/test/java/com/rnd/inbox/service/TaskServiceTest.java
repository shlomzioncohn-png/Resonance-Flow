package com.rnd.inbox.service;

import com.rnd.inbox.dto.TaskCreatedPayload;
import com.rnd.inbox.event.Event;
import com.rnd.inbox.event.EventPublisher;
import com.rnd.inbox.event.EventType;
import com.rnd.inbox.model.entity.Organization;
import com.rnd.inbox.model.entity.Project;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.model.entity.User;
import com.rnd.inbox.model.enums.SourceType;
import com.rnd.inbox.model.enums.TaskExtractionProvenance;
import com.rnd.inbox.model.enums.TaskStatus;
import com.rnd.inbox.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    private static final String SOURCE_SERVICE = "inbox";

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private EventPublisher eventPublisher;

    private TaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(taskRepository, eventPublisher, SOURCE_SERVICE);
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
