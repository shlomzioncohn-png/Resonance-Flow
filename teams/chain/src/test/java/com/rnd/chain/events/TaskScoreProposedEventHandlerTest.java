package com.rnd.chain.events;

import com.rnd.chain.dto.TaskTier;
import com.google.protobuf.Timestamp;
import com.rnd.chain.exception.TenantReassignmentException;
import com.rnd.chain.service.TaskCounterUpdater;
import com.rnd.mind.event.TaskScoreProposedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Plain unit test: mocked services, no Kafka, DB or Spring. */
class TaskScoreProposedEventHandlerTest {

    private final UUID taskId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    private TaskCounterUpdater taskCounterUpdater;
    private TaskScoreProposedEventHandler handler;

    @BeforeEach
    void setUp() {
        taskCounterUpdater = mock(TaskCounterUpdater.class);
        ProcessedEventStore store = mock(ProcessedEventStore.class);
        when(store.tryClaim(any())).thenReturn(true);
        handler = new TaskScoreProposedEventHandler(taskCounterUpdater, new ChainEventProcessingTemplate(store));
    }

    private TaskScoreProposedEvent.Builder event(float mindScore, float confidence) {
        return TaskScoreProposedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTaskId(taskId.toString())
                .setTenantId(tenantId.toString())
                .setMindScore(mindScore)
                .setConfidence(confidence);
    }

    @Test
    void validEvent_callsOnScoreProposedWithValuesRoundedToTwoDecimals() {
        handler.onMessage(event(0.8f, 0.55f)
                .setTier(com.rnd.inbox.event.TaskTier.TASK_TIER_STRATEGIC)
                .setScoredAt(Timestamp.newBuilder().setSeconds(1_700_000_000L))
                .build());

        verify(taskCounterUpdater).onScoreProposed(taskId, tenantId, TaskTier.STRATEGIC, 0.8, 0.55,
                LocalDateTime.ofEpochSecond(1_700_000_000L, 0, ZoneOffset.UTC));
    }

    @Test
    void eventWithoutScoredAt_passesNullScoredAt() {
        handler.onMessage(event(0.8f, 0.5f).build());

        verify(taskCounterUpdater).onScoreProposed(taskId, tenantId, null, 0.8, 0.5, null);
    }

    @Test
    void tenantDifferentFromStored_throwsNonRetryable() {
        doThrow(new TenantReassignmentException("tenant mismatch"))
                .when(taskCounterUpdater).onScoreProposed(any(), any(), any(), any(), any(), any());

        assertThrows(NonRetryableEventException.class, () -> handler.onMessage(event(0.8f, 0.5f).build()));
    }

    @Test
    void mindScoreOutsideRange_throwsNonRetryableAndDoesNotCallService() {
        assertThrows(NonRetryableEventException.class, () -> handler.onMessage(event(1.5f, 0.5f).build()));

        verify(taskCounterUpdater, never()).onScoreProposed(any(), any(), any(), any(), any(), any());
    }
}
