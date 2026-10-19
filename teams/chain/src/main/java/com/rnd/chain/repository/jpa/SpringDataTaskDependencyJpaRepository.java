package com.rnd.chain.repository.jpa;

import com.rnd.chain.repository.entity.TaskDependencyEntity;
import com.rnd.chain.repository.entity.TaskDependencyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SpringDataTaskDependencyJpaRepository extends JpaRepository<TaskDependencyEntity, TaskDependencyId> {

    List<TaskDependencyEntity> findByIdTaskId(UUID taskId);

    List<TaskDependencyEntity> findByIdDependsOnTaskId(UUID dependsOnTaskId);

    boolean existsByIdTaskIdAndIdDependsOnTaskId(UUID taskId, UUID dependsOnTaskId);

    long deleteByIdTaskIdAndIdDependsOnTaskId(UUID taskId, UUID dependsOnTaskId);

    /**
     * Native upsert (issue #445): inserts the edge, or - on a PK collision - updates its
     * {@code confidence} instead of no-op'ing, since Mind can republish the same edge with an
     * updated confidence (it only ever adds/updates, never removes - see
     * {@code DependencyProposedEventHandler}). {@code RETURNING (xmax = 0)} is Postgres's own
     * signal for "this row was inserted by this statement, not updated via the ON CONFLICT
     * branch" - same technique {@code PostgresConstraintRepository#save} uses, so {@code true}
     * means newly created and {@code false} means the edge already existed (confidence was still
     * written either way). Not {@code @Modifying}: the query returns a row (via RETURNING), so
     * Spring Data executes it as a query, not a bulk-update.
     */
    @Query(value = """
        INSERT INTO task_dependency (task_id, depends_on_task_id, confidence)
        VALUES (:taskId, :dependsOnTaskId, :confidence)
        ON CONFLICT (task_id, depends_on_task_id) DO UPDATE SET confidence = EXCLUDED.confidence
        RETURNING (xmax = 0)
        """, nativeQuery = true)
    boolean upsert(@Param("taskId") UUID taskId, @Param("dependsOnTaskId") UUID dependsOnTaskId,
                   @Param("confidence") Double confidence);
}
