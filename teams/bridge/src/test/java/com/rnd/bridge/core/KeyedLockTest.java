package com.rnd.bridge.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KeyedLockTest {

    @Test
    void sameKeySerializesConcurrentCallers() throws InterruptedException {
        KeyedLock lock = new KeyedLock();
        // A non-atomic read-increment-write, run concurrently under the same key: without
        // mutual exclusion this reliably loses updates (classic lost-update race); with it,
        // every increment is preserved.
        AtomicInteger unsafeCounter = new AtomicInteger(0);
        int threads = 20;
        int incrementsPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    for (int j = 0; j < incrementsPerThread; j++) {
                        lock.run("same-key", () -> {
                            int current = unsafeCounter.get();
                            unsafeCounter.set(current + 1);
                        });
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(unsafeCounter.get()).isEqualTo(threads * incrementsPerThread);
    }

    @Test
    void differentKeysDoNotBlockEachOther() throws InterruptedException {
        KeyedLock lock = new KeyedLock();
        CountDownLatch keyAHoldingLock = new CountDownLatch(1);
        CountDownLatch keyBFinished = new CountDownLatch(1);
        CountDownLatch releaseKeyA = new CountDownLatch(1);

        Thread holdsKeyA = new Thread(() -> lock.run("key-a", () -> {
            keyAHoldingLock.countDown();
            await(releaseKeyA);
        }));
        holdsKeyA.start();

        assertThat(keyAHoldingLock.await(5, TimeUnit.SECONDS)).isTrue();

        Thread usesKeyB = new Thread(() -> lock.run("key-b", keyBFinished::countDown));
        usesKeyB.start();

        // key-b must complete even though key-a's critical section is still held.
        assertThat(keyBFinished.await(2, TimeUnit.SECONDS)).isTrue();

        releaseKeyA.countDown();
        holdsKeyA.join(5000);
        usesKeyB.join(5000);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
