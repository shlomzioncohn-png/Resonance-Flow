package com.rnd.chain.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** JPA mapping for {@code task_dependency} (in the dedicated {@code chain} database - see PR
 * #417). {@code created_at} is DB-only bookkeeping - not
 * mapped back to {@link com.rnd.chain.dto.TaskDependency}, which stays a 2-field record. */
@Entity
@Table(name = "task_dependency")
public class TaskDependencyEntity {

    @EmbeddedId
    private TaskDependencyId id;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected TaskDependencyEntity() {
    }

    public TaskDependencyEntity(TaskDependencyId id) {
        this.id = id;
    }

    public TaskDependencyId getId() {
        return id;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
