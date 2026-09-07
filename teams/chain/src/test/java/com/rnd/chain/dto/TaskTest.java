package com.rnd.chain.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskTest {

    @Test
    void builder_throwsIllegalArgumentException_whenStatusIsNull() {
        assertThatThrownBy(() -> Task.builder()
                .id(UUID.randomUUID())
                .ownerId("owner")
                .status(null)
                .blockingCount(0)
                .openConstraintCount(0)
                .createdAt(LocalDateTime.now())
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
    }

    @Test
    void builder_throwsIllegalArgumentException_whenIdIsNull() {
        assertThatThrownBy(() -> newTaskBuilder()
                .id(null)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void builder_throwsIllegalArgumentException_whenOwnerIdIsNull() {
        assertThatThrownBy(() -> newTaskBuilder()
                .ownerId(null)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerId");
    }

    @Test
    void builder_throwsIllegalArgumentException_whenBlockingCountIsNegative() {
        assertThatThrownBy(() -> newTaskBuilder()
                .blockingCount(-1)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockingCount");
    }

    @Test
    void builder_throwsIllegalArgumentException_whenOpenConstraintCountIsNegative() {
        assertThatThrownBy(() -> newTaskBuilder()
                .openConstraintCount(-1)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openConstraintCount");
    }

    @Test
    void equals_isReflexive() {
        Task task = newTaskBuilder().build();

        assertThat(task).isEqualTo(task);
    }

    @Test
    void equals_isFalse_whenComparedToNull() {
        Task task = newTaskBuilder().build();

        assertThat(task.equals(null)).isFalse();
    }

    @Test
    void equals_isFalse_whenComparedToDifferentType() {
        Task task = newTaskBuilder().build();

        assertThat(task.equals("not a task")).isFalse();
    }

    @Test
    void equals_andHashCode_dependOnlyOnId() {
        UUID id = UUID.randomUUID();
        Task a = newTaskBuilder().id(id).ownerId("ownerA").status(TaskStatus.ACTIVE).build();
        Task b = newTaskBuilder().id(id).ownerId("ownerB").status(TaskStatus.DONE).blockingCount(3).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void equals_isFalse_whenIdsDiffer_evenIfOtherFieldsMatch() {
        Task a = newTaskBuilder().id(UUID.randomUUID()).build();
        Task b = newTaskBuilder().id(UUID.randomUUID()).build();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void toString_containsIdStatusAndCounts() {
        UUID id = UUID.randomUUID();
        Task task = newTaskBuilder().id(id).status(TaskStatus.ACTIVE).blockingCount(2).openConstraintCount(1).build();

        assertThat(task.toString())
                .contains(id.toString())
                .contains("ACTIVE")
                .contains("blockingCount=2")
                .contains("openConstraintCount=1");
    }

    private static Task.Builder newTaskBuilder() {
        return Task.builder()
                .id(UUID.randomUUID())
                .ownerId("owner")
                .status(TaskStatus.ACTIVE)
                .blockingCount(0)
                .openConstraintCount(0)
                .createdAt(LocalDateTime.now());
    }
}
