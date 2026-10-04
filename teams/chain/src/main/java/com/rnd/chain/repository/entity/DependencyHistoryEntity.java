package com.rnd.chain.repository.entity;

import com.rnd.chain.dto.DependencyChangeAction;
import com.rnd.chain.dto.DependencyChangeSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code dependency_history}. {@code sequence} is DB-generated (BIGSERIAL),
 * read-only from JPA's side - used only as the same-millisecond tiebreak findByTaskId's ordering
 * needs, never exposed on {@link com.rnd.chain.dto.DependencyHistoryEntry} itself. */
@Entity
@Table(name = "dependency_history")
public class DependencyHistoryEntity {

    @Id
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "depends_on_task_id", nullable = false)
    private UUID dependsOnTaskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DependencyChangeAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DependencyChangeSource source;

    @Column(name = "source_ref")
    private String sourceRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Long sequence;

    protected DependencyHistoryEntity() {
    }

    public DependencyHistoryEntity(UUID id, UUID taskId, UUID dependsOnTaskId, DependencyChangeAction action,
                                    DependencyChangeSource source, String sourceRef, Instant createdAt) {
        this.id = id;
        this.taskId = taskId;
        this.dependsOnTaskId = dependsOnTaskId;
        this.action = action;
        this.source = source;
        this.sourceRef = sourceRef;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public UUID getDependsOnTaskId() {
        return dependsOnTaskId;
    }

    public DependencyChangeAction getAction() {
        return action;
    }

    public DependencyChangeSource getSource() {
        return source;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
