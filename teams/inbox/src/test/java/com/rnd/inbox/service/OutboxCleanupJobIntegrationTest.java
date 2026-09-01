package com.rnd.inbox.service;

import com.rnd.inbox.AbstractIntegrationTest;
import com.rnd.inbox.event.EventType;
import com.rnd.inbox.model.entity.OutboxEvent;
import com.rnd.inbox.model.enums.OutboxStatus;
import com.rnd.inbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for OutboxCleanupJob against a real Postgres container.
 * Retention windows are overridden to a tiny value so the test doesn't need
 * to create rows tens of days old to be considered "old".
 */
@TestPropertySource(properties = {
        "outbox.cleanup.published-retention-days=0",
        "outbox.cleanup.failed-retention-days=0"
})
class OutboxCleanupJobIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxCleanupJob cleanupJob;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
    }

    private OutboxEvent save(OutboxStatus status, Instant createdAt) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID());
        event.setEventType(EventType.TASK_CREATED);
        event.setMessageKey(UUID.randomUUID().toString());
        event.setPayload(new HashMap<>());
        event.setStatus(status);
        event.setRetryCount((short) 0);
        event.setCreatedAt(createdAt);
        event.setNextRetryAt(createdAt);
        if (status == OutboxStatus.PUBLISHED) {
            event.setPublishedAt(createdAt);
        }
        return outboxEventRepository.save(event);
    }

    @Test
    void cleanup_removesOldPublishedAndFailedRows_butNeverPending() {
        Instant old = Instant.now().minusSeconds(60);

        OutboxEvent published = save(OutboxStatus.PUBLISHED, old);
        OutboxEvent failed = save(OutboxStatus.FAILED, old);
        OutboxEvent pending = save(OutboxStatus.PENDING, old);

        cleanupJob.cleanup();

        assertThat(outboxEventRepository.findById(published.getEventId())).isEmpty();
        assertThat(outboxEventRepository.findById(failed.getEventId())).isEmpty();
        assertThat(outboxEventRepository.findById(pending.getEventId())).isPresent();
    }
}