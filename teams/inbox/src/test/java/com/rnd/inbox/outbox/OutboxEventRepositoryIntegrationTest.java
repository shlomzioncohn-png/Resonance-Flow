package com.rnd.inbox.outbox;

import com.rnd.inbox.AbstractIntegrationTest;
import com.rnd.inbox.shared.event.EventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    private OutboxEvent newPendingEvent() {
        return saveTracked(newUnsavedEvent(Instant.now()));
    }

    private OutboxEvent newUnsavedEvent(Instant createdAt) {
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
        event.setCreatedAt(createdAt);
        event.setNextRetryAt(Instant.now());
        return event;
    }

    @Test
    void claimPendingBatch_returnsOnlyPendingRowsDueForRetry() {
        OutboxEvent duePending = newPendingEvent();

        OutboxEvent notYetDue = newPendingEvent();
        notYetDue.setNextRetryAt(Instant.now().plusSeconds(3600));
        saveTracked(notYetDue);

        OutboxEvent alreadyPublished = newPendingEvent();
        alreadyPublished.setStatus(OutboxStatus.PUBLISHED);
        alreadyPublished.setPublishedAt(Instant.now());
        saveTracked(alreadyPublished);

        List<OutboxEvent> claimed = new TransactionTemplate(transactionManager)
                .execute(status -> outboxEventRepository.claimPendingBatch(Instant.now(), 10));

        // The outbox table may hold other pending rows, so assert on this test's rows only.
        assertThat(claimed).extracting(OutboxEvent::getEventId)
                .contains(duePending.getEventId())
                .doesNotContain(notYetDue.getEventId(), alreadyPublished.getEventId());
    }

    @Test
    void claimPendingBatch_respectsBatchSizeLimit() {
        for (int i = 0; i < 5; i++) {
            newPendingEvent();
        }

        List<OutboxEvent> claimed = new TransactionTemplate(transactionManager)
                .execute(status -> outboxEventRepository.claimPendingBatch(Instant.now(), 3));

        assertThat(claimed).hasSize(3);
    }

    @Test
    void claimPendingBatch_skipsRowsLockedByAnotherConcurrentTransaction() throws Exception {
     
        OutboxEvent event = newPendingEvent();

        CountDownLatch firstTransactionHasClaimed = new CountDownLatch(1);
        CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<List<OutboxEvent>> firstClaim = executor.submit(() ->
                new TransactionTemplate(transactionManager).execute(status -> {
                    List<OutboxEvent> claimed = outboxEventRepository.claimPendingBatch(Instant.now(), 10);
                    firstTransactionHasClaimed.countDown();
                    awaitQuietly(releaseFirstTransaction);
                    return claimed;
                }));

        firstTransactionHasClaimed.await(5, TimeUnit.SECONDS);

        Future<List<OutboxEvent>> secondClaim = executor.submit(() ->
                new TransactionTemplate(transactionManager).execute(status ->
                        outboxEventRepository.claimPendingBatch(Instant.now(), 10)));

        List<OutboxEvent> second = secondClaim.get(5, TimeUnit.SECONDS);
        releaseFirstTransaction.countDown();
        List<OutboxEvent> first = firstClaim.get(5, TimeUnit.SECONDS);

        executor.shutdown();

        assertThat(first).extracting(OutboxEvent::getEventId).contains(event.getEventId());
        assertThat(second).extracting(OutboxEvent::getEventId).doesNotContain(event.getEventId());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void deletePublishedOlderThan_removesOnlyOldPublishedRows() {
        OutboxEvent oldPublished = newUnsavedEvent(Instant.now().minusSeconds(20 * 24 * 3600));
        oldPublished.setStatus(OutboxStatus.PUBLISHED);
        oldPublished.setPublishedAt(Instant.now());
        oldPublished = saveTracked(oldPublished);

        OutboxEvent recentPublished = newUnsavedEvent(Instant.now());
        recentPublished.setStatus(OutboxStatus.PUBLISHED);
        recentPublished.setPublishedAt(Instant.now());
        recentPublished = saveTracked(recentPublished);

        OutboxEvent oldButPending = newUnsavedEvent(Instant.now().minusSeconds(20 * 24 * 3600));
        oldButPending = saveTracked(oldButPending);

        int deleted = new TransactionTemplate(transactionManager)
                .execute(status -> outboxEventRepository.deletePublishedOlderThan(Instant.now().minusSeconds(7 * 24 * 3600)));

        assertThat(deleted).isEqualTo(1);
        assertThat(outboxEventRepository.findById(oldPublished.getEventId())).isEmpty();
        assertThat(outboxEventRepository.findById(recentPublished.getEventId())).isPresent();
        assertThat(outboxEventRepository.findById(oldButPending.getEventId())).isPresent();
    }

    @Test
    void deleteFailedOlderThan_removesOnlyOldFailedRows() {
        OutboxEvent oldFailed = newUnsavedEvent(Instant.now().minusSeconds(40 * 24 * 3600));
        oldFailed.setStatus(OutboxStatus.FAILED);
        oldFailed = saveTracked(oldFailed);

        int deleted = new TransactionTemplate(transactionManager)
                .execute(status -> outboxEventRepository.deleteFailedOlderThan(Instant.now().minusSeconds(30L * 24 * 3600)));

        assertThat(deleted).isEqualTo(1);
        assertThat(outboxEventRepository.findById(oldFailed.getEventId())).isEmpty();
    }
}