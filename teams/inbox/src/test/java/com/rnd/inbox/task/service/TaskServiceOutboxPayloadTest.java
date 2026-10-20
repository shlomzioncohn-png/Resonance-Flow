package com.rnd.inbox.task.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.Timestamps;
import com.rnd.inbox.audit.AuditService;
import com.rnd.inbox.event.TaskCompletedEvent;
import com.rnd.inbox.event.TaskCreatedEvent;
import com.rnd.inbox.event.TaskUpdatedEvent;
import com.rnd.inbox.outbox.OutboxEvent;
import com.rnd.inbox.outbox.OutboxEventRepository;
import com.rnd.inbox.project.ProjectRepository;
import com.rnd.inbox.task.assignment.AssignmentRepository;
import com.rnd.inbox.task.assignment.AssignmentService;
import com.rnd.inbox.task.event.TaskCompletedProtoMapper;
import com.rnd.inbox.task.event.TaskCreatedProtoMapper;
import com.rnd.inbox.task.event.TaskUpdatedProtoMapper;
import com.rnd.inbox.task.mapper.TaskMapper;
import com.rnd.inbox.task.mapper.TaskRestMapper;
import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.model.TaskExtractionProvenance;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.repository.TaskFeedbackRepository;
import com.rnd.inbox.task.repository.TaskRepository;
import com.rnd.inbox.task.security.TaskAuthorization;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.tenant.repository.TenantRepository;
import com.rnd.inbox.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Runs the outbox payload through the ObjectMapper Spring actually builds from application.yml,
// then through the Proto mappers the OutboxWorker uses - so a global Jackson setting (like the
// REST SNAKE_CASE naming) can't silently break event publishing again.
@SpringBootTest(classes = JacksonAutoConfiguration.class)
class TaskServiceOutboxPayloadTest {

    private static final Instant NOW = Instant.parse("2026-10-11T09:00:00Z");

    @Autowired
    private ObjectMapper springObjectMapper;

    private TaskRepository taskRepository;
    private OutboxEventRepository outboxEventRepository;
    private TaskService taskService;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        taskService = new TaskService(
                taskRepository,
                mock(TaskFeedbackRepository.class),
                mock(AuditService.class),
                outboxEventRepository,
                mock(AssignmentRepository.class),
                mock(TaskMapper.class),
                springObjectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(TenantRepository.class),
                mock(ProjectRepository.class),
                mock(UserRepository.class),
                new TaskAuthorization(),
                mock(TaskRestMapper.class),
                mock(AssignmentService.class));
        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
    }

    @Test
    void createTask_outboxPayloadFromSpringObjectMapper_mapsToTaskCreatedEvent() {
        Task task = restCreatedTask();
        UUID generatedId = UUID.randomUUID();
        when(taskRepository.findByTenant_IdAndSourceTypeAndSourceRef(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(taskRepository.saveAndFlush(task)).thenAnswer(invocation -> {
            task.setId(generatedId);
            return task;
        });

        taskService.createTask(task, UUID.randomUUID());

        TaskCreatedEvent event = new TaskCreatedProtoMapper(springObjectMapper).toProto(capturedOutboxEvent().getPayload());
        assertThat(event.getId()).isEqualTo(generatedId.toString());
        assertThat(event.getTenantId()).isEqualTo(tenant.getId().toString());
        assertThat(event.getTitle()).isEqualTo("Call the supplier");
        assertThat(event.getDescription()).isEmpty();
        assertThat(event.getSourceRef()).isEmpty();
        assertThat(Timestamps.toMillis(event.getCreatedAt())).isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void updateTask_outboxPayloadFromSpringObjectMapper_mapsToTaskUpdatedEvent() {
        Task task = restCreatedTask();
        task.setId(UUID.randomUUID());
        task.setUpdatedAt(NOW);
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(taskRepository.save(task)).thenReturn(task);

        taskService.updateTask(task.getId(), new Task(), UUID.randomUUID());

        TaskUpdatedEvent event = new TaskUpdatedProtoMapper().toProto(capturedOutboxEvent().getPayload());
        assertThat(event.getTaskId()).isEqualTo(task.getId().toString());
        assertThat(event.getTenantId()).isEqualTo(tenant.getId().toString());
        assertThat(event.getDescription()).isEmpty();
        assertThat(event.getSourceRef()).isEmpty();
        assertThat(Timestamps.toMillis(event.getUpdatedAt())).isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void completeTask_outboxPayloadFromSpringObjectMapper_mapsToTaskCompletedEvent() {
        Task task = restCreatedTask();
        task.setId(UUID.randomUUID());
        when(taskRepository.save(task)).thenReturn(task);

        taskService.completeTask(task, null, UUID.randomUUID());

        TaskCompletedEvent event = new TaskCompletedProtoMapper().toProto(capturedOutboxEvent().getPayload());
        assertThat(event.getTaskId()).isEqualTo(task.getId().toString());
        assertThat(event.getIsCompleted()).isTrue();
        assertThat(Timestamps.toMillis(event.getUpdatedAt())).isEqualTo(NOW.toEpochMilli());
    }

    private OutboxEvent capturedOutboxEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        return captor.getValue();
    }

    // Shaped like a task created through POST /api/v1/tasks: no description, no source_ref.
    private Task restCreatedTask() {
        Task task = new Task();
        task.setTenant(tenant);
        task.setTitle("Call the supplier");
        task.setSourceType(SourceType.MANUAL);
        task.setStatus(TaskStatus.NEW);
        task.setExtractionProvenance(TaskExtractionProvenance.MANUAL);
        task.setCreatedAt(NOW);
        return task;
    }
}
