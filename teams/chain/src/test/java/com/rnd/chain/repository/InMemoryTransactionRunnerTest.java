package com.rnd.chain.repository;

import com.rnd.chain.testsupport.ConcurrentTestRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
