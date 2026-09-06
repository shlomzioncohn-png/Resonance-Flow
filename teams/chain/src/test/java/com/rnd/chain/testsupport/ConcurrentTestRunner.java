package com.rnd.chain.testsupport;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * Runs {@code threadCount} tasks on their own threads, released together via
 * a start latch so they contend as concurrently as possible, then waits for
 * all of them to finish before returning.
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

        for (int i = 0; i < threadCount; i++) {
            int index = i;
            pool.submit(() -> {
                ready.countDown();
                await(start);
                indexedTask.accept(index);
                done.countDown();
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();
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
