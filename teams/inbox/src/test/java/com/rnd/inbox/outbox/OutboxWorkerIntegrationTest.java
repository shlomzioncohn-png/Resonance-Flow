package com.rnd.inbox.outbox;

import com.rnd.inbox.AbstractIntegrationTest;
import com.rnd.inbox.shared.event.Event;
import com.rnd.inbox.shared.event.EventPublisher;
import com.rnd.inbox.shared.event.EventType;
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

        afterFirstAttempt.setNextRetryAt(Instant.now().minusSeconds(1));
        outboxEventRepository.save(afterFirstAttempt);

        outboxWorker.pollAndPublish();

        OutboxEvent afterSecondAttempt = outboxEventRepository.findById(saved.getEventId()).orElseThrow();
        assertThat(afterSecondAttempt.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(afterSecondAttempt.getEventId()).isEqualTo(saved.getEventId());
    }
}