package com.rnd.chain.dto;

import lombok.Value;

import java.util.Objects;
import java.util.UUID;

// Identifier type: UUID, pending Inbox publishing its own task id type. Values are Task.id
// references (see Task.java) and are not validated or looked up here.
//
// @Value (PLAN.md Finding #17): both fields are already final and already participate together in
// equals/hashCode (verified against the hand-written version this replaced), so this is a safe,
// mechanical case for Lombok's all-final-fields/all-fields-equals immutable shape - unlike
// Task/Constraint, which need explicit, narrower annotations to keep their id-only equality.
@Value
public class TaskDependency {

    // taskId depends on (is blocked by) dependsOnTaskId. This class only models the edge; it does
    // not validate that either id refers to an existing Task, and it intentionally does not reject
    // self-dependencies (taskId == dependsOnTaskId) - that check belongs to the service layer,
    // which has access to the repository needed to enforce it against existing edges.
    UUID taskId;
    UUID dependsOnTaskId;

    public TaskDependency(UUID taskId, UUID dependsOnTaskId) {
        this.taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        this.dependsOnTaskId = Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
    }
}
