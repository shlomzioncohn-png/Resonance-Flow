package com.rnd.bridge.crm.enrichment;

import com.rnd.bridge.task.enrichment.EnrichmentOutput;
import com.rnd.bridge.task.model.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrichmentReplyCacheTest {

    private static EnrichmentOutput successOutput(String title) {
        return new EnrichmentOutput(null, null, title, null, null, null);
    }

    @Test
    void expiredEntryReinserted_laterEvictionKeepsTheNewEntry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        int maxEntries = 2;
        EnrichmentReplyCache cache = new EnrichmentReplyCache(clock, () -> "v1", maxEntries);

        cache.getOrCompute("crm:K1", "t", "d1", TaskStatus.NEW, null,
                () -> new EnrichmentReplyCache.Outcome.Rejected());
        cache.getOrCompute("crm:K2", "t", "d2", TaskStatus.NEW, null,
                () -> new EnrichmentReplyCache.Outcome.Success(successOutput("K2")));
        assertEquals(maxEntries, cache.size());

        clock.advance(EnrichmentReplyCache.REJECTED_TTL.plusSeconds(1));
        AtomicInteger k1Calls = new AtomicInteger();
        EnrichmentOutput k1Output = cache.getOrCompute("crm:K1", "t", "d1", TaskStatus.NEW, null, () -> {
            k1Calls.incrementAndGet();
            return new EnrichmentReplyCache.Outcome.Success(successOutput("K1-v2"));
        });
        assertEquals(1, k1Calls.get());
        assertEquals("K1-v2", k1Output.title());
        assertEquals(maxEntries, cache.size(), "re-inserting an existing key must not grow the structure");

        cache.getOrCompute("crm:K3", "t", "d3", TaskStatus.NEW, null,
                () -> new EnrichmentReplyCache.Outcome.Success(successOutput("K3")));
        assertEquals(maxEntries, cache.size());

        AtomicInteger k1CallsAfter = new AtomicInteger();
        cache.getOrCompute("crm:K1", "t", "d1", TaskStatus.NEW, null, () -> {
            k1CallsAfter.incrementAndGet();
            return new EnrichmentReplyCache.Outcome.Success(successOutput("K1-v3"));
        });
        assertEquals(0, k1CallsAfter.get(), "K1 (refreshed, most recent) must still be cached - not evicted");

        AtomicInteger k2Calls = new AtomicInteger();
        cache.getOrCompute("crm:K2", "t", "d2", TaskStatus.NEW, null, () -> {
            k2Calls.incrementAndGet();
            return new EnrichmentReplyCache.Outcome.Success(successOutput("K2-v2"));
        });
        assertEquals(1, k2Calls.get(), "K2 (the oldest surviving entry) must have been evicted");
    }

    @Test
    void sizeNeverExceedsCapacity_oldestEntryEvictedFirst() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        int maxEntries = 3;
        EnrichmentReplyCache cache = new EnrichmentReplyCache(clock, () -> "v1", maxEntries);

        for (int i = 0; i < maxEntries + 2; i++) {
            int index = i;
            cache.getOrCompute("crm:K" + i, "t", "d", TaskStatus.NEW, null,
                    () -> new EnrichmentReplyCache.Outcome.Success(successOutput("K" + index)));
            assertTrue(cache.size() <= maxEntries, "size must never exceed capacity");
        }
        assertEquals(maxEntries, cache.size());

        AtomicInteger k0Calls = new AtomicInteger();
        cache.getOrCompute("crm:K0", "t", "d", TaskStatus.NEW, null, () -> {
            k0Calls.incrementAndGet();
            return new EnrichmentReplyCache.Outcome.Success(successOutput("K0-again"));
        });
        assertEquals(1, k0Calls.get(), "the oldest entry must have been evicted first");

        AtomicInteger lastCalls = new AtomicInteger();
        cache.getOrCompute("crm:K" + (maxEntries + 1), "t", "d", TaskStatus.NEW, null, () -> {
            lastCalls.incrementAndGet();
            return new EnrichmentReplyCache.Outcome.Success(successOutput("last-again"));
        });
        assertEquals(0, lastCalls.get(), "the most recently inserted entry must still be cached");
    }

    @Test
    void concurrentPutsAndGets_leaveAConsistentCache() throws InterruptedException {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        int maxEntries = 20;
        EnrichmentReplyCache cache = new EnrichmentReplyCache(clock, () -> "v1", maxEntries);
        int threadCount = 8;
        int keysPerThread = 50;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);
        try {
            for (int t = 0; t < threadCount; t++) {
                int threadIndex = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < keysPerThread; i++) {
                            String key = "crm:T" + threadIndex + "-K" + i;
                            cache.getOrCompute(key, "t", "d", TaskStatus.NEW, null,
                                    () -> new EnrichmentReplyCache.Outcome.Success(successOutput(key)));
                            cache.getOrCompute(key, "t", "d", TaskStatus.NEW, null,
                                    () -> new EnrichmentReplyCache.Outcome.Success(successOutput(key)));
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "all threads must finish without hanging or throwing");
        } finally {
            executor.shutdownNow();
        }

        assertTrue(cache.size() <= maxEntries);
    }

    @Test
    void computeThrows_onCacheMiss_nothingIsStored_nextCallInvokesComputeAgain() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        EnrichmentReplyCache cache = new EnrichmentReplyCache(clock, () -> "v1");

        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> cache.getOrCompute(
                "crm:K1", "t", "d", TaskStatus.NEW, null, () -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("simulated compute failure");
                }));
        assertEquals(1, calls.get());
        assertEquals(0, cache.size(), "a throwing compute must not store anything - success or rejected");

        EnrichmentOutput output = cache.getOrCompute("crm:K1", "t", "d", TaskStatus.NEW, null, () -> {
            calls.incrementAndGet();
            return new EnrichmentReplyCache.Outcome.Success(successOutput("recovered"));
        });
        assertEquals(2, calls.get(), "compute must run again - the failed attempt was never cached");
        assertEquals("recovered", output.title());
    }

    @Test
    void computeThrows_originalExceptionPropagates_notConvertedToARejectedResult() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        EnrichmentReplyCache cache = new EnrichmentReplyCache(clock, () -> "v1");

        assertThrows(CallRateLimiterInterruptedException.class, () -> cache.getOrCompute(
                "crm:K1", "t", "d", TaskStatus.NEW, null, () -> {
                    throw new CallRateLimiterInterruptedException(new InterruptedException("simulated"));
                }));
        assertEquals(0, cache.size());

        AtomicInteger calls = new AtomicInteger();
        cache.getOrCompute("crm:K1", "t", "d", TaskStatus.NEW, null, () -> {
            calls.incrementAndGet();
            return new EnrichmentReplyCache.Outcome.Success(successOutput("ok"));
        });
        assertEquals(1, calls.get());
    }
}
