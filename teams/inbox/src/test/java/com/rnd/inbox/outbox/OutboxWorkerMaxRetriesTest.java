package com.rnd.inbox.outbox;

import com.rnd.inbox.AbstractIntegrationTest;
import com.rnd.inbox.shared.event.EventPublisher;
import com.rnd.inbox.shared.event.EventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Separate test class (not sharing a Spring context with the other worker
 * integration tests) because it overrides outbox.worker.max-retries to 1,
 * so a single publish failure is enough to reach FAILED without looping
 * pollAndPublish multiple times and manipulating next_retry_at manually.
 */
@TestPropertySource(properties = "outbox.worker.max-retries=1")
class OutboxWorkerMaxRetriesTest extends AbstractIntegrationTest {

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

    @Test
    void pollAndPublish_marksFailed_afterMaxRetriesReached() {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID());
        event.setEventType(EventType.TASK_CREATED);
        event.setMessageKey(UUID.randomUUID().toString());
        event.setPayload(new HashMap<>());
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount((short) 0);
        event.setCreatedAt(Instant.now());
        event.setNextRetryAt(Instant.now());
        OutboxEvent saved = outboxEventRepository.save(event);

        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("permanent failure"));
        when(eventPublisher.publish(anyString(), anyString(), any())).thenReturn(failed);

        outboxWorker.pollAndPublish();

        OutboxEvent reloaded = outboxEventRepository.findById(saved.getEventId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(reloaded.getRetryCount()).isEqualTo((short) 1);
    }
}