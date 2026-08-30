package com.rnd.chain.dto;

import java.util.Objects;
import java.util.UUID;

// Identifier type: UUID, pending Inbox publishing its own task id type. Values are Task.id
// references (see Task.java) and are not validated or looked up here.
public class TaskDependency {

    // taskId depends on (is blocked by) dependsOnTaskId. This class only models the edge; it does
    // not validate that either id refers to an existing Task, and it intentionally does not reject
    // self-dependencies (taskId == dependsOnTaskId) - that check belongs to the service layer,
    // which has access to the repository needed to enforce it against existing edges.
    // final: both fields feed equals/hashCode below, so mutating either after construction
    // could strand this object in a hash-based collection (HashSet/HashMap bucket picked at
    // insertion time would no longer match a changed hash).
    private final UUID taskId;
    private final UUID dependsOnTaskId;

    public TaskDependency(UUID taskId, UUID dependsOnTaskId) {
        this.taskId = taskId;
        this.dependsOnTaskId = dependsOnTaskId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public UUID getDependsOnTaskId() {
        return dependsOnTaskId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaskDependency)) {
            return false;
        }
        TaskDependency other = (TaskDependency) o;
        return Objects.equals(taskId, other.taskId)
                && Objects.equals(dependsOnTaskId, other.dependsOnTaskId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, dependsOnTaskId);
    }
}
