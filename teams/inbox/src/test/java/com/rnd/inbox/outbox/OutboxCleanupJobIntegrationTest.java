package com.rnd.inbox.outbox;

import com.rnd.inbox.AbstractIntegrationTest;
import com.rnd.inbox.shared.event.EventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

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

    private final List<UUID> createdEventIds = new ArrayList<>();

    // Deletes only the events this test created, never the whole outbox table.
    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAllById(createdEventIds);
        createdEventIds.clear();
    }

    // The id is generated on save, so it is only known from the saved instance.
    private OutboxEvent saveTracked(OutboxEvent event) {
        OutboxEvent saved = outboxEventRepository.save(event);
        createdEventIds.add(saved.getEventId());
        return saved;
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
        return saveTracked(event);
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