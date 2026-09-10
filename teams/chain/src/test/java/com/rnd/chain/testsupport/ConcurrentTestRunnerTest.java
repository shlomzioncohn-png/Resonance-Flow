package com.rnd.chain.testsupport;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConcurrentTestRunnerTest {

    @Test
    void run_completesNormally_whenNoTaskThrows() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger();

        ConcurrentTestRunner.run(10, counter::incrementAndGet);

        assertThat(counter.get()).isEqualTo(10);
    }

    @Test
    void run_throwsAndSurfacesTheRealException_whenATaskThrows() {
        // Regression test: previously, a task's exception vanished into a
        // discarded Future and the run silently hung until the done-latch
        // timeout, completing without ever reporting what actually broke.
        assertThatThrownBy(() -> ConcurrentTestRunner.run(5, index -> {
            if (index == 2) {
                throw new IllegalStateException("boom");
            }
        }))
                .isInstanceOf(AssertionError.class)
                .satisfies(error -> assertThat(error.getSuppressed())
                        .hasSize(1)
                        .anyMatch(suppressed -> suppressed instanceof IllegalStateException
                                && "boom".equals(suppressed.getMessage())));
    }

    @Test
    void run_surfacesEveryFailure_whenMultipleTasksThrow() {
        assertThatThrownBy(() -> ConcurrentTestRunner.run(5, index -> {
            throw new IllegalStateException("task " + index + " failed");
        }))
                .isInstanceOf(AssertionError.class)
                .satisfies(error -> assertThat(error.getSuppressed()).hasSize(5));
    }
}
