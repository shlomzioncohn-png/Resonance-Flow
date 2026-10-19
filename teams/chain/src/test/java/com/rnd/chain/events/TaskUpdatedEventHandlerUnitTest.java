package com.rnd.chain.events;

import com.google.protobuf.Timestamp;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.exception.TenantReassignmentException;
import com.rnd.chain.service.TaskEligibilityService;
import com.rnd.inbox.event.TaskUpdatedEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Pure unit test: collaborators are mocks, so it needs no database and does not depend on the schema. */
class TaskUpdatedEventHandlerUnitTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 1, 1, 12, 0);

    private final TaskEligibilityService service = mock(TaskEligibilityService.class);
    private final TaskUpdatedEventHandler handler = new TaskUpdatedEventHandler(
            service, new ChainEventProcessingTemplate(mock(ProcessedEventStore.class)));

    private static TaskUpdatedEvent event(String taskId, String tenantId) {
        return TaskUpdatedEvent.newBuilder()
                .setTaskId(taskId)
                .setTenantId(tenantId)
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_IN_PROGRESS)
                .setUpdatedAt(Timestamp.newBuilder().setSeconds(UPDATED_AT.toEpochSecond(ZoneOffset.UTC)).build())
                .build();
    }

    @Test
    void validEvent_mapsStatusAndDelegatesToTheService() {
        UUID taskId = UUID.randomUUID();

        handler.onMessage(event(taskId.toString(), TENANT_ID.toString()));

        verify(service).onTaskStatusChangedIfNewer(taskId, TaskStatus.ACTIVE, UPDATED_AT, TENANT_ID);
    }

    @Test
    void malformedTaskId_isNonRetryableAndNeverReachesTheService() {
        assertThrows(NonRetryableEventException.class,
                () -> handler.onMessage(event("not-a-uuid", TENANT_ID.toString())));

        verifyNoInteractions(service);
    }

    @Test
    void malformedTenantId_isNonRetryable() {
        assertThrows(NonRetryableEventException.class,
                () -> handler.onMessage(event(UUID.randomUUID().toString(), "not-a-uuid")));
    }

    @Test
    void unknownTask_becomesRetryableReferencedTaskNotFound() {
        UUID taskId = UUID.randomUUID();
        doThrow(new TaskNotFoundException(taskId)).when(service)
                .onTaskStatusChangedIfNewer(any(), any(), any(), any());

        assertThrows(ReferencedTaskNotFoundException.class,
                () -> handler.onMessage(event(taskId.toString(), TENANT_ID.toString())));
    }

    @Test
    void tenantReassignment_isNonRetryable() {
        doThrow(new TenantReassignmentException("tenant changed"))
                .when(service).onTaskStatusChangedIfNewer(any(), any(), any(), any());

        assertThrows(NonRetryableEventException.class,
                () -> handler.onMessage(event(UUID.randomUUID().toString(), TENANT_ID.toString())));
    }

    @Test
    void unexpectedFailure_becomesTransientSoItIsRetried() {
        doThrow(new IllegalStateException("db down")).when(service)
                .onTaskStatusChangedIfNewer(any(), any(), any(), any());

        assertThrows(ChainTransientProcessingException.class,
                () -> handler.onMessage(event(UUID.randomUUID().toString(), TENANT_ID.toString())));
    }
}
