package com.rnd.chain.events;

import com.rnd.chain.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChainOutboxCleanupJobIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private ChainOutboxEventRepository repository;

    @Autowired
    private ChainOutboxCleanupJob cleanupJob;

    @AfterEach
    void tearDown() {
        repository.deleteAll();
    }

    private ChainOutboxEvent save(ChainOutboxStatus status, Instant createdAt) {
        ChainOutboxEvent event = ChainOutboxEvent.pending(
                "task.scheduling.updated", UUID.randomUUID(), "key-" + UUID.randomUUID(),
                Map.of("taskId", "t-1"), UUID.randomUUID(), Instant.now());
        event.setStatus(status);
        event.setCreatedAt(createdAt);
        if (status == ChainOutboxStatus.PUBLISHED) {
            event.setPublishedAt(createdAt);
        }
        return repository.save(event);
    }

    @Test
    void deleteOldRows_keepsFreshRows_withinDefaultSevenDayRetention() {
        ChainOutboxEvent published = save(ChainOutboxStatus.PUBLISHED, Instant.now());
        ChainOutboxEvent failed = save(ChainOutboxStatus.FAILED, Instant.now());

        cleanupJob.deleteOldRows();

        assertTrue(repository.findById(published.getEventId()).isPresent());
        assertTrue(repository.findById(failed.getEventId()).isPresent());
    }

    @Test
    void deleteOldRows_deletesPublishedAndFailedRowsPastRetention_keepsPending() {
        Instant eightDaysAgo = Instant.now().minusSeconds(8L * 24 * 60 * 60);
        ChainOutboxEvent oldPublished = save(ChainOutboxStatus.PUBLISHED, eightDaysAgo);
        ChainOutboxEvent oldFailed = save(ChainOutboxStatus.FAILED, eightDaysAgo);
        ChainOutboxEvent oldPending = save(ChainOutboxStatus.PENDING, eightDaysAgo);

        cleanupJob.deleteOldRows();

        assertTrue(repository.findById(oldPublished.getEventId()).isEmpty(),
                "PUBLISHED rows past the retention window are deleted");
        assertTrue(repository.findById(oldFailed.getEventId()).isEmpty(),
                "FAILED rows past the retention window are deleted");
        assertTrue(repository.findById(oldPending.getEventId()).isPresent(),
                "PENDING rows are never touched by cleanup, regardless of age");
    }
}
