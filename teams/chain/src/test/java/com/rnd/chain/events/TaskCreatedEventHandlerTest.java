package com.rnd.chain.events;

import com.google.protobuf.Timestamp;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.inbox.event.TaskCreatedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class TaskCreatedEventHandlerTest {

    private static ChainEventProcessingTemplate newTemplate() {
        return new ChainEventProcessingTemplate(new InMemoryProcessedEventStore());
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static TaskCreatedEvent.Builder eventFor(UUID id, String ownerId) {
        return TaskCreatedEvent.newBuilder()
                .setId(id.toString())
                .setOwnerId(ownerId)
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_NEW)
                .setCreatedAt(timestamp(Instant.now()));
    }

    @Test
    void onMessage_forANewTask_insertsItReadyWithZeroedCounters() {
        InMemoryTaskRepository repository = new InMemoryTaskRepository(List.of());
        TaskCreatedEventHandler handler = new TaskCreatedEventHandler(repository, newTemplate());
        UUID taskId = UUID.randomUUID();

        handler.onMessage(eventFor(taskId, "owner-1").build());

        Task saved = repository.findById(taskId).orElseThrow();
        assertEquals("owner-1", saved.getOwnerId());
        assertEquals(TaskStatus.PENDING, saved.getStatus()); // TASK_STATUS_NEW maps to PENDING
        assertEquals(0, saved.getBlockingCount());
        assertEquals(0, saved.getOpenConstraintCount());
        assertTrue(saved.isReady());
    }

    @Test
    void onMessage_forAnAlreadyExistingTask_preservesEligibilityCountersAndOnlyUpdatesOwnedFields() {
        UUID taskId = UUID.randomUUID();
        Task existing = Task.builder()
                .id(taskId)
                .ownerId("stale-owner")
                .status(TaskStatus.PENDING)
                .isReady(false)
                .blockingCount(2)
                .openConstraintCount(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        InMemoryTaskRepository repository = new InMemoryTaskRepository(List.of(existing));
        TaskCreatedEventHandler handler = new TaskCreatedEventHandler(repository, newTemplate());

        handler.onMessage(eventFor(taskId, "real-owner").build());

        Task merged = repository.findById(taskId).orElseThrow();
        assertEquals("real-owner", merged.getOwnerId(), "task.created's own data must still be applied");
        assertEquals(2, merged.getBlockingCount(),
                "a dependency already recorded against this task must not be reset by a (re)delivery of task.created");
        assertEquals(1, merged.getOpenConstraintCount());
        assertFalse(merged.isReady());
    }

    @Test
    void onMessage_withAnInvalidTaskId_throwsNonRetryableEventException() {
        TaskCreatedEventHandler handler = new TaskCreatedEventHandler(
                new InMemoryTaskRepository(List.of()), newTemplate());
        TaskCreatedEvent event = eventFor(UUID.randomUUID(), "owner-1").setId("not-a-uuid").build();

        assertThrows(NonRetryableEventException.class, () -> handler.onMessage(event));
    }

    @Test
    void onMessage_whenPersistenceFails_throwsChainTransientProcessingException() {
        TaskRepository failingRepository = new TaskRepository() {
            @Override
            public Optional<Task> findById(UUID id) {
                return Optional.empty();
            }

            @Override
            public List<Task> findAllById(List<UUID> ids) {
                return List.of();
            }

            @Override
            public List<Task> findByParentTaskId(UUID parentTaskId) {
                return List.of();
            }

            @Override
            public Task save(Task task) {
                throw new RuntimeException("DB is down");
            }

            @Override
            public Optional<Task> update(UUID id, UnaryOperator<Task> mutator) {
                return Optional.empty();
            }
        };
        TaskCreatedEventHandler handler = new TaskCreatedEventHandler(failingRepository, newTemplate());
        TaskCreatedEvent event = eventFor(UUID.randomUUID(), "owner-1").build();

        assertThrows(ChainTransientProcessingException.class, () -> handler.onMessage(event));
    }
}
