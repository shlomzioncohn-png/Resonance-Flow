package com.rnd.chain.dto;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskDependencyTest {

    @Test
    void constructor_throwsNullPointerException_whenTaskIdIsNull() {
        assertThatThrownBy(() -> new TaskDependency(null, UUID.randomUUID()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("taskId");
    }

    @Test
    void constructor_throwsNullPointerException_whenDependsOnTaskIdIsNull() {
        assertThatThrownBy(() -> new TaskDependency(UUID.randomUUID(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("dependsOnTaskId");
    }

    @Test
    void getters_returnConstructorValues() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();

        TaskDependency dependency = new TaskDependency(taskId, dependsOnTaskId);

        assertThat(dependency.taskId()).isEqualTo(taskId);
        assertThat(dependency.dependsOnTaskId()).isEqualTo(dependsOnTaskId);
    }

    @Test
    void equals_isReflexive() {
        TaskDependency dependency = new TaskDependency(UUID.randomUUID(), UUID.randomUUID());

        assertThat(dependency).isEqualTo(dependency);
    }

    @Test
    void equals_isFalse_whenComparedToNull() {
        TaskDependency dependency = new TaskDependency(UUID.randomUUID(), UUID.randomUUID());

        assertThat(dependency.equals(null)).isFalse();
    }

    @Test
    void equals_isFalse_whenComparedToDifferentType() {
        TaskDependency dependency = new TaskDependency(UUID.randomUUID(), UUID.randomUUID());

        assertThat(dependency.equals("not a dependency")).isFalse();
    }

    @Test
    void equals_andHashCode_dependOnBothFields() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();

        TaskDependency a = new TaskDependency(taskId, dependsOnTaskId);
        TaskDependency b = new TaskDependency(taskId, dependsOnTaskId);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void equals_isFalse_whenTaskIdDiffers() {
        UUID dependsOnTaskId = UUID.randomUUID();
        TaskDependency a = new TaskDependency(UUID.randomUUID(), dependsOnTaskId);
        TaskDependency b = new TaskDependency(UUID.randomUUID(), dependsOnTaskId);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equals_isFalse_whenDependsOnTaskIdDiffers() {
        UUID taskId = UUID.randomUUID();
        TaskDependency a = new TaskDependency(taskId, UUID.randomUUID());
        TaskDependency b = new TaskDependency(taskId, UUID.randomUUID());

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equals_isFalse_whenTaskIdAndDependsOnTaskIdAreSwapped() {
        // TaskDependency(x, y) means "x depends on y" - the reverse edge is a different,
        // non-equal edge, not the same relationship read backwards.
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        TaskDependency forward = new TaskDependency(a, b);
        TaskDependency reverse = new TaskDependency(b, a);

        assertThat(forward).isNotEqualTo(reverse);
    }

    @Test
    void toString_containsBothIds() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();

        TaskDependency dependency = new TaskDependency(taskId, dependsOnTaskId);

        assertThat(dependency.toString())
                .contains(taskId.toString())
                .contains(dependsOnTaskId.toString());
    }
}
