package com.rnd.chain.repository.entity;

import com.rnd.chain.dto.DependencyChangeSource;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "unresolved_dependencies",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_unresolved_dependency_pair",
                        columnNames = {
                                "task_id",
                                "depends_on_task_id"
                        }),
                @UniqueConstraint(
                        name = "uq_unresolved_dependency_source_ref",
                        columnNames = {
                                "source",
                                "source_ref"
                        })
        })
public class UnresolvedDependencyEntity {

    @Id
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "depends_on_task_id", nullable = false)
    private UUID dependsOnTaskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private DependencyChangeSource source;

    @Column(name = "source_ref", nullable = false)
    private String sourceRef;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UnresolvedDependencyEntity() {
    }

    public UnresolvedDependencyEntity(
            UUID id,
            UUID taskId,
            UUID dependsOnTaskId,
            DependencyChangeSource source,
            String sourceRef,
            String reason,
            Instant createdAt) {

        this.id = id;
        this.taskId = taskId;
        this.dependsOnTaskId = dependsOnTaskId;
        this.source = source;
        this.sourceRef = sourceRef;
        this.reason = reason;
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

    public DependencyChangeSource getSource() {
        return source;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}