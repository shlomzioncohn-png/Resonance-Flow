package com.rnd.chain.repository.jpa;

import com.rnd.chain.repository.entity.TaskEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataTaskJpaRepository extends JpaRepository<TaskEntity, UUID> {

    Optional<TaskEntity> findByIdAndDeletedAtIsNull(UUID id);

    List<TaskEntity> findByIdInAndDeletedAtIsNull(List<UUID> ids);

    List<TaskEntity> findByParentTaskIdAndDeletedAtIsNull(UUID parentTaskId);

    /** Deliberately not filtering deleted_at here - the soft-delete check happens in
     * {@link com.rnd.chain.repository.PostgresTaskRepository}, against the already-locked row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TaskEntity t where t.id = :id")
    Optional<TaskEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Native upsert: {@code save()}'s contract is an unconditional overwrite, which
     * {@code entityManager.merge} cannot give safely for a client-assigned-id, {@code @Version}-
     * annotated entity Hibernate never loaded (see PERSISTENCE_PLAN.md §4). {@code version} is only
     * ever incremented here, never read/compared - save() must never throw
     * OptimisticLockException, unlike update()'s pessimistic-lock path.
     *
     * <p>{@code clearAutomatically = true}: this bypasses Hibernate's persistence context
     * entirely (a native INSERT, not a managed-entity write), so any stale TaskEntity already
     * cached for this id in the current session must be evicted - otherwise the immediate
     * findByIdAndDeletedAtIsNull read-back in PostgresTaskRepository.save() could return the
     * cached pre-upsert instance instead of hitting the database.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        INSERT INTO task (id, parent_task_id, owner_id, status, is_ready, blocking_count,
                          open_constraint_count, estimated_duration_min, score, version,
                          created_at, updated_at, deleted_at)
        VALUES (:id, :parentTaskId, :ownerId, :status, :isReady, :blockingCount,
                :openConstraintCount, :estimatedDurationMin, :score, 0,
                :createdAt, :updatedAt, :deletedAt)
        ON CONFLICT (id) DO UPDATE SET
            parent_task_id = EXCLUDED.parent_task_id, owner_id = EXCLUDED.owner_id,
            status = EXCLUDED.status, is_ready = EXCLUDED.is_ready,
            blocking_count = EXCLUDED.blocking_count,
            open_constraint_count = EXCLUDED.open_constraint_count,
            estimated_duration_min = EXCLUDED.estimated_duration_min, score = EXCLUDED.score,
            created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at,
            deleted_at = EXCLUDED.deleted_at, version = task.version + 1
        """, nativeQuery = true)
    void upsert(@Param("id") UUID id, @Param("parentTaskId") UUID parentTaskId,
                @Param("ownerId") String ownerId, @Param("status") String status,
                @Param("isReady") boolean isReady, @Param("blockingCount") int blockingCount,
                @Param("openConstraintCount") int openConstraintCount,
                @Param("estimatedDurationMin") Integer estimatedDurationMin,
                @Param("score") Double score, @Param("createdAt") LocalDateTime createdAt,
                @Param("updatedAt") LocalDateTime updatedAt, @Param("deletedAt") LocalDateTime deletedAt);
}
