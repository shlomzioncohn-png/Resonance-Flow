package com.rnd.chain.events;

import com.google.protobuf.Timestamp;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.inbox.event.TaskCreatedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pure unit test: collaborators are mocks, so it needs no database and does not depend on the schema. */
class TaskCreatedEventHandlerUnitTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final TaskCreatedEventHandler handler = new TaskCreatedEventHandler(
            taskRepository, new ChainEventProcessingTemplate(mock(ProcessedEventStore.class)));

    private static TaskCreatedEvent event(String id) {
        return TaskCreatedEvent.newBuilder()
                .setId(id)
                .setTenantId(TENANT_ID.toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_NEW)
                .setCreatedAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
                .build();
    }

    @Test
    void newTask_isInsertedReadyWithZeroCounters() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.update(eq(taskId), any())).thenReturn(Optional.empty());

        handler.onMessage(event(taskId.toString()));

        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(saved.capture());
        assertEquals(taskId, saved.getValue().getId());
        assertEquals(TENANT_ID, saved.getValue().getTenantId());
        assertEquals(TaskStatus.PENDING, saved.getValue().getStatus());
        assertTrue(saved.getValue().isReady());
        assertEquals(0, saved.getValue().getBlockingCount());
        assertEquals(0, saved.getValue().getOpenConstraintCount());
    }

    @Test
    void existingTask_isMergedNotInserted() {
        UUID taskId = UUID.randomUUID();
        Task existing = Task.builder().id(taskId).status(TaskStatus.PENDING).build();
        when(taskRepository.update(eq(taskId), any())).thenReturn(Optional.of(existing));

        handler.onMessage(event(taskId.toString()));

        verify(taskRepository, never()).save(any());
    }

    @Test
    void malformedId_isNonRetryable() {
        assertThrows(NonRetryableEventException.class, () -> handler.onMessage(event("not-a-uuid")));
    }

    @Test
    void repositoryFailure_becomesTransientSoItIsRetried() {
        doThrow(new IllegalStateException("db down")).when(taskRepository).update(any(), any());

        assertThrows(ChainTransientProcessingException.class,
                () -> handler.onMessage(event(UUID.randomUUID().toString())));
    }
}
