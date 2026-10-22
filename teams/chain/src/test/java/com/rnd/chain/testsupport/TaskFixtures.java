package com.rnd.chain.testsupport;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/** Shared test-fixture builder for a plain, ready, no-dependency Task. */
public final class TaskFixtures {

    /** Shared by every {@link #newTask(UUID)} (issue #580), so two tasks built from this fixture
     * always pass the strict same-tenant dependency check without each call site needing to set
     * one explicitly. */
    public static final UUID DEFAULT_TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID OTHER_TENANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private TaskFixtures() {
    }

    public static Task newTask(UUID id) {
        return Task.builder()
                .id(id)
                .tenantId(DEFAULT_TENANT_ID)
                .status(TaskStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
