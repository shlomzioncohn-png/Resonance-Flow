package com.rnd.chain.dto;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TaskDependencyTest {

    @Test
    void constructor_nullTaskId_throwsNpe() {
        UUID dependsOnTaskId = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> new TaskDependency(null, dependsOnTaskId));
    }

    @Test
    void constructor_nullDependsOnTaskId_throwsNpe() {
        UUID taskId = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> new TaskDependency(taskId, null));
    }

    @Test
    void equals_sameTaskIdAndDependsOnTaskId_areEqual() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();

        TaskDependency a = new TaskDependency(taskId, dependsOnTaskId);
        TaskDependency b = new TaskDependency(taskId, dependsOnTaskId);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentTaskId_areNotEqual() {
        UUID dependsOnTaskId = UUID.randomUUID();
        TaskDependency a = new TaskDependency(UUID.randomUUID(), dependsOnTaskId);
        TaskDependency b = new TaskDependency(UUID.randomUUID(), dependsOnTaskId);

        assertNotEquals(a, b);
    }

    @Test
    void equals_differentDependsOnTaskId_areNotEqual() {
        UUID taskId = UUID.randomUUID();
        TaskDependency a = new TaskDependency(taskId, UUID.randomUUID());
        TaskDependency b = new TaskDependency(taskId, UUID.randomUUID());

        assertNotEquals(a, b);
    }

    @Test
    void equals_reversedEdgeDirection_areNotEqual() {
        // TaskDependency(x, y) means "x depends on y" - the reverse edge is a different,
        // non-equal edge, not the same relationship read backwards.
        UUID x = UUID.randomUUID();
        UUID y = UUID.randomUUID();

        TaskDependency xDependsOnY = new TaskDependency(x, y);
        TaskDependency yDependsOnX = new TaskDependency(y, x);

        assertNotEquals(xDependsOnY, yDependsOnX);
    }

    @Test
    void gettersReturnConstructorValues() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();

        TaskDependency dependency = new TaskDependency(taskId, dependsOnTaskId);

        assertEquals(taskId, dependency.getTaskId());
        assertEquals(dependsOnTaskId, dependency.getDependsOnTaskId());
    }
}
