package com.rnd.chain.events;

import com.rnd.chain.dto.ProcessedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InMemoryProcessedEventStore had no test coverage at all before this -
 * despite being a stateful component explicitly intended to support
 * concurrent access (it backs ConcurrentHashMap on purpose). The
 * concurrency test below is a regression test for the exact race the
 * code review flagged: two listener threads (or a duplicate delivery
 * landing on two partitions) could previously both see "not yet
 * processed" under isProcessed()-then-markProcessed() and both proceed.
 */
class InMemoryProcessedEventStoreTest {

    private static final int THREAD_COUNT = 16;

    @Test
    void tryClaim_returnsTrue_forANewEventId() {
        InMemoryProcessedEventStore store = new InMemoryProcessedEventStore();
        ProcessedEvent event = new ProcessedEvent(UUID.randomUUID(), "task.created", Instant.now());

        assertTrue(store.tryClaim(event));
    }

    @Test
    void tryClaim_returnsFalse_whenTheSameEventIdIsClaimedAgain() {
        InMemoryProcessedEventStore store = new InMemoryProcessedEventStore();
        UUID eventId = UUID.randomUUID();
        ProcessedEvent first = new ProcessedEvent(eventId, "task.created", Instant.now());
        ProcessedEvent secondDelivery = new ProcessedEvent(eventId, "task.created", Instant.now());

        assertTrue(store.tryClaim(first));
        assertFalse(store.tryClaim(secondDelivery));
    }

    @Test
    void tryClaim_treatsDifferentEventIdsIndependently() {
        InMemoryProcessedEventStore store = new InMemoryProcessedEventStore();
        ProcessedEvent first = new ProcessedEvent(UUID.randomUUID(), "task.created", Instant.now());
        ProcessedEvent second = new ProcessedEvent(UUID.randomUUID(), "task.created", Instant.now());

        assertTrue(store.tryClaim(first));
        assertTrue(store.tryClaim(second));
    }

    @Test
    void release_allowsTheSameEventIdToBeClaimedAgain() {
        InMemoryProcessedEventStore store = new InMemoryProcessedEventStore();
        UUID eventId = UUID.randomUUID();
        store.tryClaim(new ProcessedEvent(eventId, "task.created", Instant.now()));

        store.release(eventId);

        assertTrue(store.tryClaim(new ProcessedEvent(eventId, "task.created", Instant.now())),
                "after release, the same eventId must be claimable again (e.g. a retry after a handler failure)");
    }

    @Test
    void release_ofAnUnclaimedEventId_doesNothing() {
        InMemoryProcessedEventStore store = new InMemoryProcessedEventStore();

        assertDoesNotThrow(() -> store.release(UUID.randomUUID()));
    }

    @Test
    void tryClaim_underConcurrency_exactlyOneCallerWinsForTheSameEventId() throws InterruptedException {
        InMemoryProcessedEventStore store = new InMemoryProcessedEventStore();
        UUID eventId = UUID.randomUUID();

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);
        AtomicInteger winners = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    ProcessedEvent event = new ProcessedEvent(eventId, "task.created", Instant.now());
                    if (store.tryClaim(event)) {
                        winners.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "workers failed to start");
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish in time");
        pool.shutdown();

        assertEquals(1, winners.get(), "exactly one concurrent tryClaim for the same eventId must win");
    }
}
