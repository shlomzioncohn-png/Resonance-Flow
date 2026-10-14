package com.rnd.chain.testsupport;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/** Shared test-fixture builder for a plain, ready, no-dependency Task. */
public final class TaskFixtures {

    private TaskFixtures() {
    }

    public static Task newTask(UUID id) {
        return Task.builder()
                .id(id)
                .status(TaskStatus.ACTIVE)
                .isReady(true)
                .blockingCount(0)
                .openConstraintCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
