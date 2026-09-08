package com.rnd.chain.dto;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TaskTest {

    @Test
    void builder_nullId_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskTestFixtures.defaultTask(null).build());
    }

    @Test
    void builder_nullOwnerId_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskTestFixtures.defaultTask(UUID.randomUUID()).ownerId(null).build());
    }

    @Test
    void builder_negativeBlockingCount_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskTestFixtures.defaultTask(UUID.randomUUID()).blockingCount(-1).build());
    }

    @Test
    void builder_negativeOpenConstraintCount_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskTestFixtures.defaultTask(UUID.randomUUID()).openConstraintCount(-1).build());
    }

    @Test
    void copyOf_null_throwsNpe() {
        assertThrows(NullPointerException.class, () -> Task.copyOf(null));
    }

    @Test
    void copyOf_producesIndependentInstance() {
        Task source = TaskTestFixtures.defaultTask(UUID.randomUUID()).blockingCount(1).build();

        Task copy = Task.copyOf(source);
        copy.setBlockingCount(99);
        copy.setStatus(TaskStatus.DONE);

        assertEquals(1, source.getBlockingCount());
        assertNotEquals(TaskStatus.DONE, source.getStatus());
    }

    @Test
    void equals_sameId_areEqual_regardlessOfOtherFields() {
        UUID id = UUID.randomUUID();
        Task a = TaskTestFixtures.defaultTask(id).blockingCount(0).status(TaskStatus.ACTIVE).build();
        Task b = TaskTestFixtures.defaultTask(id).blockingCount(5).status(TaskStatus.DONE).build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentId_areNotEqual() {
        Task a = TaskTestFixtures.defaultTask(UUID.randomUUID()).build();
        Task b = TaskTestFixtures.defaultTask(UUID.randomUUID()).build();

        assertNotEquals(a, b);
    }
}
