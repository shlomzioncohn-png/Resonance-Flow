package com.rnd.chain.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key mirroring {@code task_dependency}'s (task_id, depends_on_task_id) PK. */
@Embeddable
public class TaskDependencyId implements Serializable {

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "depends_on_task_id")
    private UUID dependsOnTaskId;

    protected TaskDependencyId() {
    }

    public TaskDependencyId(UUID taskId, UUID dependsOnTaskId) {
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
        if (!(o instanceof TaskDependencyId)) {
            return false;
        }
        TaskDependencyId other = (TaskDependencyId) o;
        return Objects.equals(taskId, other.taskId) && Objects.equals(dependsOnTaskId, other.dependsOnTaskId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, dependsOnTaskId);
    }
}
