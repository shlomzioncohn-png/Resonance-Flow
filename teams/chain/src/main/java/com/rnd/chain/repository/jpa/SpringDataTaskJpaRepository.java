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

    /** Bulk form of {@link #findByIdForUpdate}. Rows are locked in a stable order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TaskEntity t where t.id in :ids order by t.id")
    List<TaskEntity> findByIdInForUpdate(@Param("ids") List<UUID> ids);

    /** Native upsert. {@code clearAutomatically = true} evicts any TaskEntity for this id already
     * cached in the persistence context. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        INSERT INTO task (id, parent_task_id, status, is_ready, blocking_count,
                          open_constraint_count, estimated_duration_min, score, version,
                          created_at, updated_at, deleted_at)
        VALUES (:id, :parentTaskId, :status, :isReady, :blockingCount,
                :openConstraintCount, :estimatedDurationMin, :score, 0,
                :createdAt, :updatedAt, :deletedAt)
        ON CONFLICT (id) DO UPDATE SET
            parent_task_id = EXCLUDED.parent_task_id,
            status = EXCLUDED.status,
            is_ready = EXCLUDED.is_ready,
            blocking_count = EXCLUDED.blocking_count,
            open_constraint_count = EXCLUDED.open_constraint_count,
            estimated_duration_min = EXCLUDED.estimated_duration_min,
            score = EXCLUDED.score,
            created_at = EXCLUDED.created_at,
            updated_at = EXCLUDED.updated_at,
            deleted_at = EXCLUDED.deleted_at,
            version = task.version + 1
        """, nativeQuery = true)
    void upsert(@Param("id") UUID id,
                @Param("parentTaskId") UUID parentTaskId,
                @Param("status") String status,
                @Param("isReady") boolean isReady,
                @Param("blockingCount") int blockingCount,
                @Param("openConstraintCount") int openConstraintCount,
                @Param("estimatedDurationMin") Integer estimatedDurationMin,
                @Param("score") Double score,
                @Param("createdAt") LocalDateTime createdAt,
                @Param("updatedAt") LocalDateTime updatedAt,
                @Param("deletedAt") LocalDateTime deletedAt);

    /**
     * Inserts the task only if a row with the same id does not already exist.
     *
     * @return number of inserted rows: 1 if inserted, 0 if the task already existed.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        INSERT INTO task (id, parent_task_id, status, is_ready, blocking_count,
                          open_constraint_count, estimated_duration_min, score, version,
                          created_at, updated_at, deleted_at)
        VALUES (:id, :parentTaskId, :status, :isReady, :blockingCount,
                :openConstraintCount, :estimatedDurationMin, :score, 0,
                :createdAt, :updatedAt, :deletedAt)
        ON CONFLICT (id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("parentTaskId") UUID parentTaskId,
                       @Param("status") String status,
                       @Param("isReady") boolean isReady,
                       @Param("blockingCount") int blockingCount,
                       @Param("openConstraintCount") int openConstraintCount,
                       @Param("estimatedDurationMin") Integer estimatedDurationMin,
                       @Param("score") Double score,
                       @Param("createdAt") LocalDateTime createdAt,
                       @Param("updatedAt") LocalDateTime updatedAt,
                       @Param("deletedAt") LocalDateTime deletedAt);
}