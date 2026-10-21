package com.rnd.chain.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskTest {

    @Test
    void builder_throwsIllegalArgumentException_whenStatusIsNull() {
        assertThatThrownBy(() -> TaskTestFixtures.defaultTask(UUID.randomUUID())
                .status(null)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
    }

    @Test
    void builder_throwsIllegalArgumentException_whenIdIsNull() {
        assertThatThrownBy(() -> TaskTestFixtures.defaultTask(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }



    @Test
    void builder_throwsIllegalArgumentException_whenBlockingCountIsNegative() {
        assertThatThrownBy(() -> TaskTestFixtures.defaultTask(UUID.randomUUID())
                .blockingCount(-1)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockingCount");
    }

    @Test
    void builder_throwsIllegalArgumentException_whenOpenConstraintCountIsNegative() {
        assertThatThrownBy(() -> TaskTestFixtures.defaultTask(UUID.randomUUID())
                .openConstraintCount(-1)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openConstraintCount");
    }

    @Test
    void copyOf_throwsNullPointerException_whenSourceIsNull() {
        assertThatThrownBy(() -> Task.copyOf(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void copyOf_producesEqualButDistinctInstance() {
        Task source = TaskTestFixtures.defaultTask(UUID.randomUUID()).blockingCount(1).build();

        Task copy = Task.copyOf(source);

        assertThat(copy).isNotSameAs(source).isEqualTo(source);
        assertThat(copy.getBlockingCount()).isEqualTo(source.getBlockingCount());
        assertThat(copy.getStatus()).isEqualTo(source.getStatus());
    }

    @Test
    void copyOf_andToBuilder_copyEveryField() {
        LocalDateTime created = LocalDateTime.of(2026, 1, 2, 3, 4);
        LocalDateTime updated = LocalDateTime.of(2026, 5, 6, 7, 8);
        LocalDateTime deleted = LocalDateTime.of(2026, 9, 10, 11, 12);
        Task source = Task.builder()
                .id(UUID.randomUUID())
                .parentTaskId(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .status(TaskStatus.ACTIVE)
                .isReady(true)
                .blockingCount(2)
                .openConstraintCount(3)
                .estimatedDurationMin(45)
                .score(0.7)
                .confidence(0.8)
                .priorityScore(0.9)
                .tier(TaskTier.TACTICAL)
                .createdAt(created)
                .updatedAt(updated)
                .deletedAt(deleted)
                .build();

        assertThat(Task.copyOf(source)).usingRecursiveComparison().isEqualTo(source);
        assertThat(source.toBuilder().build()).usingRecursiveComparison().isEqualTo(source);
    }

    @Test
    void equals_isReflexive() {
        Task task = TaskTestFixtures.defaultTask(UUID.randomUUID()).build();

        assertThat(task).isEqualTo(task);
    }

    @Test
    void equals_isFalse_whenComparedToNull() {
        Task task = TaskTestFixtures.defaultTask(UUID.randomUUID()).build();

        assertThat(task.equals(null)).isFalse();
    }

    @Test
    void equals_isFalse_whenComparedToDifferentType() {
        Task task = TaskTestFixtures.defaultTask(UUID.randomUUID()).build();

        assertThat(task.equals("not a task")).isFalse();
    }

    @Test
    void equals_andHashCode_dependOnlyOnId() {
        UUID id = UUID.randomUUID();
        Task a = TaskTestFixtures.defaultTask(id).status(TaskStatus.ACTIVE).build();
        Task b = TaskTestFixtures.defaultTask(id).status(TaskStatus.DONE).blockingCount(3).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void equals_isFalse_whenIdsDiffer_evenIfOtherFieldsMatch() {
        Task a = TaskTestFixtures.defaultTask(UUID.randomUUID()).build();
        Task b = TaskTestFixtures.defaultTask(UUID.randomUUID()).build();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void toString_containsIdStatusAndCounts() {
        UUID id = UUID.randomUUID();
        Task task = TaskTestFixtures.defaultTask(id).status(TaskStatus.ACTIVE).blockingCount(2).openConstraintCount(1).build();

        assertThat(task.toString())
                .contains(id.toString())
                .contains("ACTIVE")
                .contains("blockingCount=2")
                .contains("openConstraintCount=1");
    }
}
