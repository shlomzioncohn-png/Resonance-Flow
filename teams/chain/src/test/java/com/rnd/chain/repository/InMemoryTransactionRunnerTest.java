package com.rnd.chain.repository;

import com.rnd.chain.testsupport.ConcurrentTestRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryTransactionRunnerTest {

    private InMemoryTransactionRunner runner;

    @BeforeEach
    void setUp() {
        runner = new InMemoryTransactionRunner();
    }

    @Test
    void runInTransaction_returnsTheActionsResult() {
        String result = runner.runInTransaction(() -> "value");

        assertThat(result).isEqualTo("value");
    }

    @Test
    void runInTransaction_propagatesExceptionThrownByAction() {
        RuntimeException failure = new RuntimeException("boom");

        assertThatThrownBy(() -> runner.runInTransaction(() -> {
            throw failure;
        })).isSameAs(failure);
    }

    @Test
    void runInTransaction_serializesConcurrentCalls() throws InterruptedException {
        int threadCount = 20;
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxObservedActive = new AtomicInteger();

        ConcurrentTestRunner.run(threadCount, () -> runner.runInTransaction(() -> {
            int current = active.incrementAndGet();
            maxObservedActive.updateAndGet(max -> Math.max(max, current));
            active.decrementAndGet();
            return null;
        }));

        assertThat(maxObservedActive.get()).isEqualTo(1);
    }

    // --- runInReadOnlyTransaction: concurrent with other reads, exclusive against writes -------

    @Test
    void runInReadOnlyTransaction_returnsTheActionsResult() {
        String result = runner.runInReadOnlyTransaction(() -> "value");

        assertThat(result).isEqualTo("value");
    }

    @Test
    void runInReadOnlyTransaction_propagatesExceptionThrownByAction() {
        RuntimeException failure = new RuntimeException("boom");

        assertThatThrownBy(() -> runner.runInReadOnlyTransaction(() -> {
            throw failure;
        })).isSameAs(failure);
    }

    @Test
    void runInReadOnlyTransaction_allowsConcurrentReads() throws InterruptedException {
        int threadCount = 20;
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxObservedActive = new AtomicInteger();

        ConcurrentTestRunner.run(threadCount, () -> runner.runInReadOnlyTransaction(() -> {
            int current = active.incrementAndGet();
            maxObservedActive.updateAndGet(max -> Math.max(max, current));
            sleepBriefly();
            active.decrementAndGet();
            return null;
        }));

        assertThat(maxObservedActive.get()).isGreaterThan(1);
    }

    @Test
    void runInReadOnlyTransaction_blocksUntilAnInFlightWriteFinishes() throws InterruptedException {
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);

        Thread writer = new Thread(() -> runner.runInTransaction(() -> {
            writeStarted.countDown();
            awaitUpTo2Seconds(releaseWrite);
            return null;
        }));
        writer.start();
        assertThat(writeStarted.await(2, TimeUnit.SECONDS)).isTrue();

        AtomicBoolean readReturned = new AtomicBoolean(false);
        Thread reader = new Thread(() -> {
            runner.runInReadOnlyTransaction(() -> null);
            readReturned.set(true);
        });
        reader.start();

        Thread.sleep(200);
        assertThat(readReturned.get()).isFalse();

        releaseWrite.countDown();
        reader.join(2000);
        assertThat(readReturned.get()).isTrue();
        writer.join(2000);
    }

    @Test
    void runInTransaction_waitsForAnInFlightReadToFinish() throws InterruptedException {
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);

        Thread reader = new Thread(() -> runner.runInReadOnlyTransaction(() -> {
            readStarted.countDown();
            awaitUpTo2Seconds(releaseRead);
            return null;
        }));
        reader.start();
        assertThat(readStarted.await(2, TimeUnit.SECONDS)).isTrue();

        AtomicBoolean writeReturned = new AtomicBoolean(false);
        Thread writer = new Thread(() -> {
            runner.runInTransaction(() -> null);
            writeReturned.set(true);
        });
        writer.start();

        Thread.sleep(200);
        assertThat(writeReturned.get()).isFalse();

        releaseRead.countDown();
        writer.join(2000);
        assertThat(writeReturned.get()).isTrue();
        reader.join(2000);
    }

    private static void awaitUpTo2Seconds(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
