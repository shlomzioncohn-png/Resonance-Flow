package com.rnd.inbox.service;

import com.rnd.inbox.event.Event;
import com.rnd.inbox.event.EventPublisher;
import com.rnd.inbox.event.EventType;
import com.rnd.inbox.model.entity.OutboxEvent;
import com.rnd.inbox.model.enums.OutboxStatus;
import com.rnd.inbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for OutboxWorker's internal logic - no database or Kafka
 * involved. OutboxEventRepository and EventPublisher are mocked, so these
 * run instantly and don't need Docker/Testcontainers.
 */
class OutboxWorkerTest {

    private static final int MAX_RETRIES = 3;
    private static final long BACKOFF_BASE_SECONDS = 10;
    private static final long BACKOFF_MAX_SECONDS = 120;

    private OutboxEventRepository outboxEventRepository;
    private EventPublisher eventPublisher;
    private OutboxWorker worker;

    @BeforeEach
    void setUp() {
        outboxEventRepository = mock(OutboxEventRepository.class);
        eventPublisher = mock(EventPublisher.class);
        worker = new OutboxWorker(outboxEventRepository, eventPublisher,
                20, MAX_RETRIES, BACKOFF_BASE_SECONDS, BACKOFF_MAX_SECONDS);
    }

    private OutboxEvent pendingEvent() {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID());
        event.setEventType(EventType.TASK_CREATED);
        event.setAggregateId(UUID.randomUUID());
        event.setMessageKey("some-task-id");
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", "some-task-id");
        event.setPayload(payload);
        event.setCorrelationId(UUID.randomUUID());
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount((short) 0);
        event.setCreatedAt(Instant.now());
        event.setNextRetryAt(Instant.now());
        return event;
    }

    @Test
    void processOne_marksEventPublished_whenPublishSucceeds() {
        OutboxEvent event = pendingEvent();
        when(eventPublisher.publish(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        worker.processOne(event);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getRetryCount()).isZero();
    }

    @Test
    void processOne_sendsTopicFromEventType_notAStoredColumn() {
        OutboxEvent event = pendingEvent();
        when(eventPublisher.publish(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        worker.processOne(event);

        verify(eventPublisher).publish(eq(EventType.TASK_CREATED.getTopic()), eq(event.getMessageKey()), any());
    }

    @Test
    void processOne_sendsSameEventId_onEveryAttempt() {
        // This is the guarantee behind "publish succeeded but the outbox
        // update failed - the same event can be republished with the same
        // event_id": the eventId sent to Kafka always comes from the outbox
        // row itself (via Event.existing), never freshly generated, so
        // calling processOne again for the same row sends the same eventId.
        OutboxEvent event = pendingEvent();
        when(eventPublisher.publish(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        ArgumentCaptor<Event<Map<String, Object>>> captor = ArgumentCaptor.forClass(Event.class);

        worker.processOne(event);
        worker.processOne(event);

        verify(eventPublisher, org.mockito.Mockito.times(2)).publish(anyString(), anyString(), captor.capture());
        var sentEvents = captor.getAllValues();
        assertThat(sentEvents.get(0).getEventId()).isEqualTo(sentEvents.get(1).getEventId());
        assertThat(sentEvents.get(0).getEventId()).isEqualTo(event.getEventId());
    }

    @Test
    void processOne_incrementsRetryAndSchedulesBackoff_whenPublishFails() {
        OutboxEvent event = pendingEvent();
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("kafka unavailable"));
        when(eventPublisher.publish(anyString(), anyString(), any())).thenReturn(failed);

        Instant before = Instant.now();
        worker.processOne(event);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isEqualTo((short) 1);
        assertThat(event.getLastError()).contains("kafka unavailable");
        assertThat(event.getNextRetryAt()).isAfter(before);
    }

    @Test
    void processOne_marksFailed_afterMaxRetriesReached() {
        OutboxEvent event = pendingEvent();
        event.setRetryCount((short) (MAX_RETRIES - 1)); // this attempt will be the MAX_RETRIES-th
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("permanent failure"));
        when(eventPublisher.publish(anyString(), anyString(), any())).thenReturn(failed);

        worker.processOne(event);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo((short) MAX_RETRIES);
    }

    @Test
    void computeBackoff_doublesEachAttempt_upToMax() {
        assertThat(worker.computeBackoff(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(worker.computeBackoff(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(worker.computeBackoff(3)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void computeBackoff_neverExceedsConfiguredMax() {
        // 10 * 2^9 = 5120s, far above the 120s max configured in setUp()
        assertThat(worker.computeBackoff(10)).isEqualTo(Duration.ofSeconds(BACKOFF_MAX_SECONDS));
    }

    @Test
    void pollAndPublish_doesNotThrow_whenClaimQueryFails() {
        when(outboxEventRepository.claimPendingBatch(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new RuntimeException("DB connection lost"));

        // Should be caught and logged internally, not propagate - this is
        // what lets @Scheduled keep running on the next cycle instead of
        // Spring's scheduler silently disabling future runs after an error.
        worker.pollAndPublish();
    }
}