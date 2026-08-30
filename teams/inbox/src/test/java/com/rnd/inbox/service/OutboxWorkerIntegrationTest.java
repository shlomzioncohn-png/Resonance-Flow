package com.rnd.inbox.service;

import com.rnd.inbox.AbstractIntegrationTest;
import com.rnd.inbox.event.Event;
import com.rnd.inbox.event.EventPublisher;
import com.rnd.inbox.event.EventType;
import com.rnd.inbox.model.entity.OutboxEvent;
import com.rnd.inbox.model.enums.OutboxStatus;
import com.rnd.inbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration tests for OutboxWorker against a real Postgres container.
 * EventPublisher is still mocked here - these tests are about the outbox
 * mechanism (claim, status transitions, retry persistence) working
 * correctly against a real database and a real Spring context, not about
 * actually reaching a Kafka broker.
 */
class OutboxWorkerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxWorker outboxWorker;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockBean
    private EventPublisher eventPublisher;

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
    }

    private OutboxEvent savePendingEvent() {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID());
        event.setEventType(EventType.TASK_CREATED);
        event.setAggregateId(UUID.randomUUID());
        event.setMessageKey(UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        payload.put("hello", "world");
        event.setPayload(payload);
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount((short) 0);
        event.setCreatedAt(Instant.now());
        event.setNextRetryAt(Instant.now());
        return outboxEventRepository.save(event);
    }

    @Test
    void pollAndPublish_marksPendingEventPublished_onSuccess() {
        OutboxEvent saved = savePendingEvent();
        when(eventPublisher.publish(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        outboxWorker.pollAndPublish();

        OutboxEvent reloaded = outboxEventRepository.findById(saved.getEventId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(reloaded.getPublishedAt()).isNotNull();
    }

    @Test
    void pollAndPublish_thenSucceedsOnSubsequentRun_afterInitialFailure() {
        // Simulates a service restart with a still-pending event: the first
        // run fails and schedules a retry; once next_retry_at has passed
        // (simulated here by moving it into the past directly, rather than
        // sleeping for the real backoff), a later run - just like after a
        // restart - picks the same row up again and succeeds.
        OutboxEvent saved = savePendingEvent();
        CompletableFuture<Void> failedOnce = new CompletableFuture<>();
        failedOnce.completeExceptionally(new RuntimeException("kafka temporarily down"));
        when(eventPublisher.publish(anyString(), anyString(), any()))
                .thenReturn(failedOnce)
                .thenReturn(CompletableFuture.completedFuture(null));

        outboxWorker.pollAndPublish();

        OutboxEvent afterFirstAttempt = outboxEventRepository.findById(saved.getEventId()).orElseThrow();
        assertThat(afterFirstAttempt.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterFirstAttempt.getRetryCount()).isEqualTo((short) 1);

        // Force the retry to be due now, instead of waiting for the real backoff.
        afterFirstAttempt.setNextRetryAt(Instant.now().minusSeconds(1));
        outboxEventRepository.save(afterFirstAttempt);

        outboxWorker.pollAndPublish();

        OutboxEvent afterSecondAttempt = outboxEventRepository.findById(saved.getEventId()).orElseThrow();
        assertThat(afterSecondAttempt.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(afterSecondAttempt.getEventId()).isEqualTo(saved.getEventId()); // same event_id preserved across attempts
    }
}