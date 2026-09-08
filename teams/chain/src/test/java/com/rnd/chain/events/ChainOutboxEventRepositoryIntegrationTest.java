package com.rnd.chain.events;

import com.rnd.chain.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChainOutboxEventRepositoryIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private ChainOutboxEventRepository repository;

    @AfterEach
    void tearDown() {
        repository.deleteAll();
    }

    private ChainOutboxEvent save(Instant nextRetryAt) {
        ChainOutboxEvent event = ChainOutboxEvent.pending(
                "task.scheduling.updated", UUID.randomUUID(), "key-" + UUID.randomUUID(),
                Map.of("taskId", "t-1", "isAvailable", true), UUID.randomUUID(), Instant.now());
        event.setNextRetryAt(nextRetryAt);
        return repository.save(event);
    }

    @Test
    void save_generatesEventIdAndRoundTripsJsonbPayload() {
        ChainOutboxEvent saved = save(Instant.now());

        assertNotNull(saved.getEventId());
        ChainOutboxEvent reloaded = repository.findById(saved.getEventId()).orElseThrow();
        assertEquals(Map.of("taskId", "t-1", "isAvailable", true), reloaded.getPayload());
        assertEquals(ChainOutboxStatus.PENDING, reloaded.getStatus());
    }

    @Test
    void claimPendingBatch_returnsOnlyDuePendingRows() {
        ChainOutboxEvent due = save(Instant.now().minusSeconds(10));
        ChainOutboxEvent notYetDue = save(Instant.now().plusSeconds(3600));
        ChainOutboxEvent alreadyPublished = save(Instant.now().minusSeconds(10));
        alreadyPublished.setStatus(ChainOutboxStatus.PUBLISHED);
        alreadyPublished.setPublishedAt(Instant.now());
        repository.save(alreadyPublished);

        List<ChainOutboxEvent> claimed = repository.claimPendingBatch(Instant.now(), 20);

        List<UUID> claimedIds = claimed.stream().map(ChainOutboxEvent::getEventId).toList();
        assertTrue(claimedIds.contains(due.getEventId()));
        assertFalse(claimedIds.contains(notYetDue.getEventId()));
        assertFalse(claimedIds.contains(alreadyPublished.getEventId()));
    }

    @Test
    void claimPendingBatch_respectsBatchSizeLimit() {
        for (int i = 0; i < 5; i++) {
            save(Instant.now().minusSeconds(10));
        }

        List<ChainOutboxEvent> claimed = repository.claimPendingBatch(Instant.now(), 3);

        assertEquals(3, claimed.size());
    }

    @Test
    void deletePublishedOlderThan_deletesOnlyOldPublishedRows() {
        ChainOutboxEvent oldPublished = save(Instant.now());
        oldPublished.setStatus(ChainOutboxStatus.PUBLISHED);
        oldPublished.setPublishedAt(Instant.now().minusSeconds(3600));
        repository.save(oldPublished);

        ChainOutboxEvent recentPending = save(Instant.now());

        int deleted = repository.deletePublishedOlderThan(Instant.now());

        assertEquals(1, deleted);
        assertTrue(repository.findById(oldPublished.getEventId()).isEmpty());
        assertTrue(repository.findById(recentPending.getEventId()).isPresent());
    }

    @Test
    void deleteFailedOlderThan_deletesOnlyOldFailedRows() {
        ChainOutboxEvent oldFailed = save(Instant.now());
        oldFailed.setStatus(ChainOutboxStatus.FAILED);
        repository.save(oldFailed);

        int deleted = repository.deleteFailedOlderThan(Instant.now());

        assertEquals(1, deleted);
        assertTrue(repository.findById(oldFailed.getEventId()).isEmpty());
    }
}
