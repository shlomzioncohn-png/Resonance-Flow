package com.rnd.chain.events;

import com.google.protobuf.Timestamp;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.service.UnresolvedDependencyService;
import com.rnd.inbox.event.TaskCreatedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskCreatedEventHandlerTest {

    private static ChainEventProcessingTemplate newTemplate() {
        return new ChainEventProcessingTemplate(
                new InMemoryProcessedEventStore());
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static TaskCreatedEvent.Builder eventFor(UUID id) {

        return TaskCreatedEvent.newBuilder()
                .setId(id.toString())
                .setTenantId(UUID.randomUUID().toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_NEW)
                .setCreatedAt(timestamp(Instant.now()));
    }

    @Test
    void onMessage_forANewTask_insertsItReadyWithZeroedCounters() {

        InMemoryTaskRepository repository =
                new InMemoryTaskRepository(List.of());

        UnresolvedDependencyService unresolvedService =
                mock(UnresolvedDependencyService.class);

        TaskCreatedEventHandler handler =
                new TaskCreatedEventHandler(
                        repository,
                        newTemplate(),
                        unresolvedService);

        UUID taskId = UUID.randomUUID();

        handler.onMessage(
                eventFor(taskId).build());

        Task saved =
                repository.findById(taskId).orElseThrow();


        assertEquals(
                TaskStatus.PENDING,
                saved.getStatus());

        assertEquals(
                0,
                saved.getBlockingCount());

        assertEquals(
                0,
                saved.getOpenConstraintCount());

        assertTrue(saved.isReady());

        verify(unresolvedService)
                .resolveWaitingForTask(taskId);
    }

    @Test
    void onMessage_forAnAlreadyExistingTask_preservesEligibilityCountersAndOnlyUpdatesOwnedFields() {

        UUID taskId = UUID.randomUUID();

        Task existing = Task.builder()
                .id(taskId)
                .status(TaskStatus.PENDING)
                .isReady(false)
                .blockingCount(2)
                .openConstraintCount(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        InMemoryTaskRepository repository =
                new InMemoryTaskRepository(
                        List.of(existing));

        UnresolvedDependencyService unresolvedService =
                mock(UnresolvedDependencyService.class);

        TaskCreatedEventHandler handler =
                new TaskCreatedEventHandler(
                        repository,
                        newTemplate(),
                        unresolvedService);

        handler.onMessage(
                eventFor(taskId).build());

        Task merged =
                repository.findById(taskId).orElseThrow();


        assertEquals(
                2,
                merged.getBlockingCount());

        assertEquals(
                1,
                merged.getOpenConstraintCount());

        assertFalse(merged.isReady());

        verify(unresolvedService)
                .resolveWaitingForTask(taskId);
    }

    @Test
    void onMessage_withAnInvalidTaskId_throwsNonRetryableEventException() {

        UnresolvedDependencyService unresolvedService =
                mock(UnresolvedDependencyService.class);

        TaskCreatedEventHandler handler =
                new TaskCreatedEventHandler(
                        new InMemoryTaskRepository(List.of()),
                        newTemplate(),
                        unresolvedService);

        TaskCreatedEvent event =
                eventFor(
                        UUID.randomUUID())
                        .setId("not-a-uuid")
                        .build();

        assertThrows(
                NonRetryableEventException.class,
                () -> handler.onMessage(event));

        verifyNoInteractions(unresolvedService);
    }

    @Test
    void onMessage_whenPersistenceFails_throwsChainTransientProcessingException() {

        TaskRepository failingRepository =
                new TaskRepository() {

                    @Override
                    public Optional<Task> findById(UUID id) {
                        return Optional.empty();
                    }

                    @Override
                    public List<Task> findAllById(
                            List<UUID> ids) {

                        return List.of();
                    }

                    @Override
                    public List<Task> findByParentTaskId(
                            UUID parentTaskId) {

                        return List.of();
                    }

                    @Override
                    public Task save(Task task) {
                        throw new RuntimeException(
                                "DB is down");
                    }
                    @Override
                    public boolean insertIfAbsent(Task task) {
                        throw new RuntimeException("DB is down");
                    }

                    @Override
                    public Optional<Task> update(
                            UUID id,
                            UnaryOperator<Task> mutator) {

                        return Optional.empty();
                    }

                    @Override
                    public List<Task> updateAll(
                            List<UUID> ids,
                            UnaryOperator<Task> mutator) {

                        return List.of();
                    }
                };

        UnresolvedDependencyService unresolvedService =
                mock(UnresolvedDependencyService.class);

        TaskCreatedEventHandler handler =
                new TaskCreatedEventHandler(
                        failingRepository,
                        newTemplate(),
                        unresolvedService);

        TaskCreatedEvent event =
                eventFor(
                        UUID.randomUUID()
)
                        .build();

        assertThrows(
                ChainTransientProcessingException.class,
                () -> handler.onMessage(event));

        verifyNoInteractions(unresolvedService);
    }
}