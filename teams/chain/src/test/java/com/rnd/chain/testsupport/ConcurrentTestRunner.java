package com.rnd.chain.testsupport;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * Runs {@code threadCount} tasks on their own threads, released together via
 * a start latch so they contend as concurrently as possible, then waits for
 * all of them to finish before returning. If any task throws, or either
 * latch times out, {@code run} fails with an {@link AssertionError} carrying
 * every failure that occurred (as suppressed exceptions) instead of hanging
 * silently until the timeout and failing on an unrelated assertion.
 */
public final class ConcurrentTestRunner {

    private ConcurrentTestRunner() {
    }

    public static void run(int threadCount, Runnable task) throws InterruptedException {
        run(threadCount, index -> task.run());
    }

    public static void run(int threadCount, IntConsumer indexedTask) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threadCount; i++) {
            int index = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    await(start);
                    indexedTask.accept(index);
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for " + threadCount + " threads to become ready");
            }
            start.countDown();
            if (!done.await(10, TimeUnit.SECONDS)) {
                throw withFailuresSuppressed(new AssertionError("Timed out waiting for " + threadCount
                        + " threads to finish (" + failures.size() + " had already failed)"), failures);
            }
        } finally {
            pool.shutdown();
        }

        if (!failures.isEmpty()) {
            throw withFailuresSuppressed(
                    new AssertionError(failures.size() + " of " + threadCount + " concurrent tasks failed"),
                    failures);
        }
    }

    private static AssertionError withFailuresSuppressed(AssertionError error, Queue<Throwable> failures) {
        failures.forEach(error::addSuppressed);
        return error;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
