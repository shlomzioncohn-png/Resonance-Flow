package com.rnd.chain.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Shared Task test-fixture builder (review finding #23) - three test classes
 * (InMemoryTaskRepositoryTest, TaskEligibilityServiceConcurrencyTest,
 * TaskEligibilityServiceTest) each used to hand-roll their own private buildTask() helper with a
 * different ad-hoc parameter list, even though Task's shape has already changed more than once
 * in this project's history. Centralizing the sensible defaults here means a future shape change
 * only needs updating in one place, and each test overrides only the field(s) it actually cares
 * about via the returned builder's own fluent chain.
 */
public final class TaskTestFixtures {

    private TaskTestFixtures() {
    }

    public static Task.Builder defaultTask(UUID id) {
        return Task.builder()
                .id(id)
                .ownerId("owner")
                .status(TaskStatus.ACTIVE)
                .isReady(false)
                .blockingCount(0)
                .openConstraintCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now());
    }
}
