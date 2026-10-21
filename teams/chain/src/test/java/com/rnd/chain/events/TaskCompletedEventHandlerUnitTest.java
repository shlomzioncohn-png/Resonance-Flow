package com.rnd.chain.events;

import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.service.TaskEligibilityService;
import com.rnd.inbox.event.TaskCompletedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Pure unit test: collaborators are mocks, so it needs no database and does not depend on the schema. */
class TaskCompletedEventHandlerUnitTest {

    private static final LocalDateTime KAFKA_TIME = LocalDateTime.of(2026, 1, 1, 12, 0);

    private final TaskEligibilityService service = mock(TaskEligibilityService.class);
    private final TaskCompletedEventHandler handler = new TaskCompletedEventHandler(
            service, new ChainEventProcessingTemplate(mock(ProcessedEventStore.class)));

    private static ConsumerRecord<String, TaskCompletedEvent> record(String taskId, boolean completed, long timestamp) {
        TaskCompletedEvent event = TaskCompletedEvent.newBuilder().setTaskId(taskId).setIsCompleted(completed).build();
        return new ConsumerRecord<>("task.completed", 0, 0L, timestamp, TimestampType.CREATE_TIME, 0, 0,
                taskId, event, new RecordHeaders(), Optional.empty());
    }

    private static long millis(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    @Test
    void completedEvent_callsOnTaskCompletedWithTheKafkaRecordTime() {
        UUID taskId = UUID.randomUUID();

        handler.onMessage(record(taskId.toString(), true, millis(KAFKA_TIME)));

        verify(service).onTaskCompleted(taskId, KAFKA_TIME);
    }

    @Test
    void isCompletedFalse_isIgnored() {
        handler.onMessage(record(UUID.randomUUID().toString(), false, millis(KAFKA_TIME)));

        verifyNoInteractions(service);
    }

    @Test
    void malformedTaskId_isNonRetryable() {
        assertThrows(NonRetryableEventException.class,
                () -> handler.onMessage(record("not-a-uuid", true, millis(KAFKA_TIME))));
    }

    @Test
    void missingKafkaTimestamp_isNonRetryable() {
        assertThrows(NonRetryableEventException.class,
                () -> handler.onMessage(record(UUID.randomUUID().toString(), true, -1L)));
    }

    @Test
    void unknownTask_becomesTransientSoItIsRetried() {
        UUID taskId = UUID.randomUUID();
        doThrow(new TaskNotFoundException(taskId)).when(service).onTaskCompleted(any(), any());

        assertThrows(ChainTransientProcessingException.class,
                () -> handler.onMessage(record(taskId.toString(), true, millis(KAFKA_TIME))));
    }

    @Test
    void unexpectedFailure_becomesTransientSoItIsRetried() {
        doThrow(new IllegalStateException("db down")).when(service).onTaskCompleted(any(), any());

        assertThrows(ChainTransientProcessingException.class,
                () -> handler.onMessage(record(UUID.randomUUID().toString(), true, millis(KAFKA_TIME))));
    }
}
