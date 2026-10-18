package com.rnd.chain.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** JPA mapping for {@code task_dependency} (in the dedicated {@code chain} database - see PR
 * #417). {@code created_at} is DB-only bookkeeping - not
 * mapped back to {@link com.rnd.chain.dto.TaskDependency}, which stays a 3-field record
 * (issue #445 added {@code confidence}, which - unlike {@code created_at} - IS mapped back). */
@Entity
@Table(name = "task_dependency")
public class TaskDependencyEntity {

    @EmbeddedId
    private TaskDependencyId id;

    /** Issue #445: Mind's confidence (0..1) in this specific edge - see
     * {@code com.rnd.chain.dto.TaskDependency#confidence()}. Read-only here: writes go through
     * {@code SpringDataTaskDependencyJpaRepository#upsert}'s native query, not entity dirty
     * checking, so this field is never actually set by application code on an existing instance -
     * only populated by Hibernate when reading a row back. */
    @Column(insertable = false, updatable = false)
    private Double confidence;

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

    public Double getConfidence() {
        return confidence;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
