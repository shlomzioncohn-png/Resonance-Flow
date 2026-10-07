package com.rnd.chain.events;

import com.google.protobuf.Timestamp;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.service.UnresolvedDependencyService;
import com.rnd.inbox.event.TaskCreatedEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskCreatedEventServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private UnresolvedDependencyService unresolvedDependencyService;

    private TaskCreatedEventService service;

    @BeforeEach
    void setUp() {
        service =
                new TaskCreatedEventService(
                        taskRepository,
                        unresolvedDependencyService);
    }

    @Test
    void handle_shouldSaveTaskAndResolveWaitingDependencies() {

        UUID taskId = UUID.randomUUID();

        TaskCreatedEvent event =
                TaskCreatedEvent.newBuilder()
                        .setId(taskId.toString())
                        .setIsAvailable(true)
                        .setStatus(
                                com.rnd.inbox.event.TaskStatus
                                        .TASK_STATUS_IN_PROGRESS)
                        .setCreatedAt(
                                Timestamp.newBuilder()
                                        .setSeconds(1_760_000_000L)
                                        .build())
                        .build();

        service.handle(event);

        ArgumentCaptor<Task> taskCaptor =
                ArgumentCaptor.forClass(Task.class);

        verify(taskRepository)
                .save(taskCaptor.capture());

        Task savedTask = taskCaptor.getValue();

        assertEquals(taskId, savedTask.getId());
        assertEquals(TaskStatus.ACTIVE, savedTask.getStatus());
        assertEquals(true, savedTask.isReady());

        var ordered =
                inOrder(taskRepository, unresolvedDependencyService);

        ordered.verify(taskRepository)
                .save(savedTask);

        ordered.verify(unresolvedDependencyService)
                .resolveWaitingForTask(taskId);
    }

    @Test
    void handle_whenStatusDone_shouldMapToDone() {

        UUID taskId = UUID.randomUUID();

        TaskCreatedEvent event =
                TaskCreatedEvent.newBuilder()
                        .setId(taskId.toString())
                        .setIsAvailable(false)
                        .setStatus(
                                com.rnd.inbox.event.TaskStatus
                                        .TASK_STATUS_DONE)
                        .setCreatedAt(
                                Timestamp.newBuilder()
                                        .setSeconds(1_760_000_000L)
                                        .build())
                        .build();

        service.handle(event);

        ArgumentCaptor<Task> taskCaptor =
                ArgumentCaptor.forClass(Task.class);

        verify(taskRepository)
                .save(taskCaptor.capture());

        assertEquals(
                TaskStatus.DONE,
                taskCaptor.getValue().getStatus());
    }

    @Test
    void handle_whenTaskIdInvalid_shouldThrowAndNotResolve() {

        TaskCreatedEvent event =
                TaskCreatedEvent.newBuilder()
                        .setId("not-a-uuid")
                        .setCreatedAt(
                                Timestamp.newBuilder()
                                        .setSeconds(1_760_000_000L)
                                        .build())
                        .build();

        assertThrows(
                NonRetryableEventException.class,
                () -> service.handle(event));
    }
    @Test
void handle_whenTaskAlreadyExists_shouldNotOverwriteChainState() {

    UUID taskId = UUID.randomUUID();

    Task existingTask =
            Task.builder()
                    .id(taskId)
                    .parentTaskId(null)
                    .ownerId(null)
                    .status(TaskStatus.ACTIVE)
                    .isReady(false)
                    .blockingCount(3)
                    .openConstraintCount(2)
                    .estimatedDurationMin(60)
                    .score(7.5)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .deletedAt(null)
                    .build();

    when(taskRepository.findById(taskId))
            .thenReturn(Optional.of(existingTask));

    TaskCreatedEvent event =
            TaskCreatedEvent.newBuilder()
                    .setId(taskId.toString())
                    .setIsAvailable(true)
                    .setStatus(
                            com.rnd.inbox.event.TaskStatus
                                    .TASK_STATUS_IN_PROGRESS)
                    .setCreatedAt(
                            Timestamp.newBuilder()
                                    .setSeconds(1_760_000_000L)
                                    .build())
                    .build();

    service.handle(event);

    verify(taskRepository, never())
            .save(any());

    verify(unresolvedDependencyService)
            .resolveWaitingForTask(taskId);
}
}