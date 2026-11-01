package com.rnd.chain.repository.jpa;

import com.rnd.chain.dto.TaskStatus;
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
    List<TaskEntity> findByTenantIdAndDeletedAtIsNull(UUID tenantId);
    List<TaskEntity> findByTenantIdAndStatusNotAndDeletedAtIsNull(UUID tenantId, TaskStatus status);

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
        INSERT INTO task (id, parent_task_id, tenant_id, status,
                          estimated_duration_min, score, confidence,
                          priority_score, tier, scored_at, version, created_at, updated_at, deleted_at)
        VALUES (:id, :parentTaskId, :tenantId, :status,
                :estimatedDurationMin, :score, :confidence, :priorityScore,
                CAST(:tier AS VARCHAR), :scoredAt, 0, :createdAt, :updatedAt, :deletedAt)
        ON CONFLICT (id) DO UPDATE SET
            parent_task_id = EXCLUDED.parent_task_id,
            tenant_id = EXCLUDED.tenant_id,
            status = EXCLUDED.status,
            estimated_duration_min = EXCLUDED.estimated_duration_min, score = EXCLUDED.score,
            confidence = EXCLUDED.confidence, priority_score = EXCLUDED.priority_score,
            tier = COALESCE(EXCLUDED.tier, task.tier), scored_at = EXCLUDED.scored_at,
            created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at,
            deleted_at = EXCLUDED.deleted_at, version = task.version + 1
        """, nativeQuery = true)
    void upsert(@Param("id") UUID id,
                @Param("parentTaskId") UUID parentTaskId,
                @Param("tenantId") UUID tenantId,
                @Param("status") String status,
                @Param("estimatedDurationMin") Integer estimatedDurationMin,
                @Param("score") Double score, @Param("confidence") Double confidence,
                @Param("priorityScore") Double priorityScore,
                @Param("tier") String tier,
                @Param("scoredAt") LocalDateTime scoredAt,
                @Param("createdAt") LocalDateTime createdAt,
                @Param("updatedAt") LocalDateTime updatedAt, @Param("deletedAt") LocalDateTime deletedAt);

    /**
     * Inserts the task only if a row with the same id does not already exist.
     *
     * @return number of inserted rows: 1 if inserted, 0 if the task already existed.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        INSERT INTO task (id, parent_task_id, tenant_id, status,
                          estimated_duration_min, score, tier, version,
                          created_at, updated_at, deleted_at)
        VALUES (:id, :parentTaskId, :tenantId, :status,
                :estimatedDurationMin, :score, CAST(:tier AS VARCHAR), 0,
                :createdAt, :updatedAt, :deletedAt)
        ON CONFLICT (id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("parentTaskId") UUID parentTaskId,
                       @Param("tenantId") UUID tenantId,
                       @Param("status") String status,
                       @Param("estimatedDurationMin") Integer estimatedDurationMin,
                       @Param("score") Double score,
                       @Param("tier") String tier,
                       @Param("createdAt") LocalDateTime createdAt,
                       @Param("updatedAt") LocalDateTime updatedAt,
                       @Param("deletedAt") LocalDateTime deletedAt);
}
