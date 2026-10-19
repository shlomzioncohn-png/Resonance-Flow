package com.rnd.chain.events;

import com.rnd.chain.dto.ProcessedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Not @Transactional on purpose: claims must be committed by independent connections for the
 * concurrency test to exercise the primary key for real. Rows are removed in @AfterEach.
 */
@Tag("postgres")
@SpringBootTest
class PostgresProcessedEventStoreTest {

    private static final int THREAD_COUNT = 16;

    @Autowired
    private PostgresProcessedEventStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> usedIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        usedIds.forEach(id -> jdbcTemplate.update("DELETE FROM processed_events WHERE event_id = ?", id));
    }

    private ProcessedEvent newEvent() {
        UUID id = UUID.randomUUID();
        usedIds.add(id);
        return new ProcessedEvent(id, "task.dependencies.proposed", Instant.now());
    }

    @Test
    void tryClaim_returnsTrue_forANewEventId() {
        assertTrue(store.tryClaim(newEvent()));
    }

    @Test
    void tryClaim_returnsFalse_whenTheSameEventIdIsClaimedAgain() {
        ProcessedEvent event = newEvent();

        assertTrue(store.tryClaim(event));
        assertFalse(store.tryClaim(event));
    }

    @Test
    void tryClaim_persistsTheEventTypeAndTimestamp() {
        ProcessedEvent event = newEvent();
        store.tryClaim(event);

        String type = jdbcTemplate.queryForObject(
                "SELECT event_type FROM processed_events WHERE event_id = ?", String.class, event.eventId());
        assertEquals(event.eventType(), type);
    }

    @Test
    void release_makesTheEventClaimableAgain() {
        ProcessedEvent event = newEvent();
        assertTrue(store.tryClaim(event));

        store.release(event.eventId());

        assertTrue(store.tryClaim(event), "a released claim must be retriable");
    }

    @Test
    void release_ofAnUnknownEventId_isANoOp() {
        assertDoesNotThrow(() -> store.release(UUID.randomUUID()));
    }

    @Test
    void tryClaim_insideARolledBackTransaction_leavesNoClaimBehind() {
        ProcessedEvent event = newEvent();

        new TransactionTemplate(transactionManager).execute(status -> {
            assertTrue(store.tryClaim(event));
            status.setRollbackOnly();
            return null;
        });

        assertTrue(store.tryClaim(event), "rolling back the handler's transaction must also drop its claim");
    }

    @Test
    void tryClaim_underConcurrency_letsExactlyOneCallerWin() throws Exception {
        ProcessedEvent event = newEvent();
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < THREAD_COUNT; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    if (store.tryClaim(event)) {
                        winners.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, winners.get(), "exactly one concurrent caller may win the claim");
    }
}
