package com.rnd.chain.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Shared Task test-fixture builder (review finding #23) - test classes used to hand-roll their
 * own private buildTask() helper with a different ad-hoc parameter list, even though Task's shape
 * has already changed more than once in this project's history. Centralizing the sensible
 * defaults here means a future shape change only needs updating in one place.
 *
 * <p>{@code TaskEligibilityServiceConcurrencyTest} and {@code TaskTest} call this directly.
 * {@code TaskEligibilityServiceTest} keeps its own private {@code buildTask(id, blockingCount,
 * openConstraintCount, isReady[, status])} - a positional-args convenience wrapper used at 30+
 * call sites in that file - but its body now delegates to {@link #defaultTask(UUID)} rather than
 * duplicating the {@code Task.builder()} construction, so this class is still the single place
 * the actual defaults live. {@code InMemoryTaskRepositoryTest} doesn't use either form - it
 * extends {@code TaskRepositoryContractTest}, which has its own fixture.
 */
public final class TaskTestFixtures {

    private TaskTestFixtures() {
    }

    public static Task.Builder defaultTask(UUID id) {
        return Task.builder()
                .id(id)
                .status(TaskStatus.ACTIVE)
                .isReady(false)
                .blockingCount(0)
                .openConstraintCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now());
    }
}
