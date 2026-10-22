package com.rnd.chain.repository.entity;

import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** JPA mapping for {@code task} (in the dedicated {@code chain} database - see PR #417).
 * Mapped to/from {@link com.rnd.chain.dto.Task} entirely
 * inside {@link com.rnd.chain.repository.PostgresTaskRepository} - never crosses that boundary. */
@Entity
@Table(name = "task")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskEntity {

    @Id
    private UUID id;

    @Column(name = "parent_task_id")
    private UUID parentTaskId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    @Column(name = "is_ready", nullable = false)
    private boolean isReady;

    @Column(name = "blocking_count", nullable = false)
    private int blockingCount;

    @Column(name = "open_constraint_count", nullable = false)
    private int openConstraintCount;

    @Column(name = "estimated_duration_min")
    private Integer estimatedDurationMin;

    private Double score;

    /** Issue #445: Mind's confidence (0..1) in {@link #score}. */
    private Double confidence;

    /** Issue #445: Chain's feasibility-adjusted priority, 1..10, or null until {@link #score}
     * is set - see {@code PriorityScoreService}. */
    @Column(name = "priority_score")
    private Double priorityScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier")
    private TaskTier tier;

    @Column(name = "scored_at")
    private LocalDateTime scoredAt;

    @Version
    @Setter(AccessLevel.NONE)
    private long version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
