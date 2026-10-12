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
 *
 * <p>{@link #DEFAULT_TENANT_ID} (issue #580): every {@link #defaultTask(UUID)} shares this same
 * constant, so two tasks built from this fixture always share a tenant and pass the strict
 * same-tenant dependency check without every call site needing to set one explicitly. A test that
 * specifically exercises the cross-tenant rejection should override with
 * {@code .tenantId(OTHER_TENANT_ID)} (or any other value) on one side.
 */
public final class TaskTestFixtures {

    public static final UUID DEFAULT_TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID OTHER_TENANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private TaskTestFixtures() {
    }

    public static Task.Builder defaultTask(UUID id) {
        return Task.builder()
                .id(id)
                .ownerId("owner")
                .tenantId(DEFAULT_TENANT_ID)
                .status(TaskStatus.ACTIVE)
                .isReady(false)
                .blockingCount(0)
                .openConstraintCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now());
    }
}
