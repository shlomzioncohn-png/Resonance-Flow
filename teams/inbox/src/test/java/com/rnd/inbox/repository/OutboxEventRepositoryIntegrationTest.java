package com.rnd.inbox.repository;

import com.rnd.inbox.AbstractIntegrationTest;
import com.rnd.inbox.event.EventType;
import com.rnd.inbox.model.entity.OutboxEvent;
import com.rnd.inbox.model.enums.OutboxStatus;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for OutboxEventRepository against a real Postgres
 * container - claimPendingBatch relies on FOR UPDATE SKIP LOCKED, which is
 * real database locking behavior that cannot be faithfully simulated with
 * mocks.
 */
class OutboxEventRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
    }

    private OutboxEvent newPendingEvent() {
        return outboxEventRepository.save(newUnsavedEvent(Instant.now()));
    }

    // created_at is updatable = false (it's meant to be immutable once
    // written), so a row that needs a backdated created_at must have it set
    // before the first save - setting it on an already-persisted entity and
    // saving again silently keeps the original value.
    //
    // OutboxEvent.eventId uses a Hibernate-assigned @GeneratedValue, which
    // overwrites the id set here on insert. Because the id is already
    // non-null before that first save, Spring Data treats it as an update
    // (merge) rather than a fresh persist, so the generated id only comes
    // back on the object returned by save() - the original reference keeps
    // the discarded id. Callers must reassign from save()'s return value
    // before using getEventId().
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
        outboxEventRepository.save(notYetDue);

        OutboxEvent alreadyPublished = newPendingEvent();
        alreadyPublished.setStatus(OutboxStatus.PUBLISHED);
        alreadyPublished.setPublishedAt(Instant.now());
        outboxEventRepository.save(alreadyPublished);

        List<OutboxEvent> claimed = new TransactionTemplate(transactionManager)
                .execute(status -> outboxEventRepository.claimPendingBatch(Instant.now(), 10));

        assertThat(claimed).extracting(OutboxEvent::getEventId).containsExactly(duePending.getEventId());
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
        // Simulates two INBOX instances polling at the same moment: this
        // test proves SKIP LOCKED actually prevents both from claiming the
        // same rows, using two real, concurrently open transactions rather
        // than mocked repository calls.
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

        assertThat(first).extracting(OutboxEvent::getEventId).containsExactly(event.getEventId());
        assertThat(second).isEmpty(); // row was locked by the first transaction, SKIP LOCKED skips it
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
        oldPublished = outboxEventRepository.save(oldPublished);

        OutboxEvent recentPublished = newUnsavedEvent(Instant.now());
        recentPublished.setStatus(OutboxStatus.PUBLISHED);
        recentPublished.setPublishedAt(Instant.now());
        recentPublished = outboxEventRepository.save(recentPublished);

        OutboxEvent oldButPending = newUnsavedEvent(Instant.now().minusSeconds(20 * 24 * 3600));
        oldButPending = outboxEventRepository.save(oldButPending);

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
        oldFailed = outboxEventRepository.save(oldFailed);

        int deleted = new TransactionTemplate(transactionManager)
                .execute(status -> outboxEventRepository.deleteFailedOlderThan(Instant.now().minusSeconds(30L * 24 * 3600)));

        assertThat(deleted).isEqualTo(1);
        assertThat(outboxEventRepository.findById(oldFailed.getEventId())).isEmpty();
    }
}