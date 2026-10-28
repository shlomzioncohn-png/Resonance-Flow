package com.rnd.bridge.crm.enrichment;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallRateLimiterTest {

    @Test
    void realSleeper_interrupted_throwsAndRestoresInterruptFlag() throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptedAfter = new AtomicBoolean();

        Thread worker = new Thread(() -> {
            try {
                CallRateLimiter.realSleeper().accept(Duration.ofSeconds(30));
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                interruptedAfter.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.start();
        // Give the worker time to actually enter Thread.sleep before interrupting it.
        Thread.sleep(200);
        worker.interrupt();
        worker.join(5000);

        assertInstanceOf(CallRateLimiterInterruptedException.class, thrown.get());
        assertTrue(interruptedAfter.get(), "the interrupt flag must be restored before the exception propagates");
    }

    @Test
    void interruptedWait_doesNotUpdateLastCallStartedAt_subsequentWaitStillMeasuredFromPriorCall() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        List<Duration> sleeps = new ArrayList<>();
        AtomicBoolean interruptNextSleep = new AtomicBoolean(false);
        CallRateLimiter rateLimiter = new CallRateLimiter(Duration.ofSeconds(4), clock, duration -> {
            if (interruptNextSleep.get()) {
                Thread.currentThread().interrupt();
                throw new CallRateLimiterInterruptedException(new InterruptedException("simulated"));
            }
            sleeps.add(duration);
        });

        // Call 1 (T0): establishes lastCallStartedAt - no prior call, no sleep.
        rateLimiter.awaitTurn();

        // Call 2 (T0+1s): interrupted while waiting - must NOT update lastCallStartedAt.
        clock.advance(Duration.ofSeconds(1));
        interruptNextSleep.set(true);
        try {
            assertThrows(CallRateLimiterInterruptedException.class, rateLimiter::awaitTurn);
        } finally {
            Thread.interrupted(); // clear this test thread's flag before the next awaitTurn()
        }
        interruptNextSleep.set(false);

        // Call 3 (T0+2s): if lastCallStartedAt had wrongly moved to T0+1s (the interrupted
        // call), the remaining wait here would be 4s-1s=3s. It must still be measured from
        // the last call that actually completed (T0), i.e. 4s-2s=2s.
        clock.advance(Duration.ofSeconds(1));
        rateLimiter.awaitTurn();

        assertEquals(1, sleeps.size());
        assertEquals(Duration.ofSeconds(2), sleeps.get(0));
    }
}
