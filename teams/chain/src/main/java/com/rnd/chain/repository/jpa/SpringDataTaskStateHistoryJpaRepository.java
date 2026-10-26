package com.rnd.chain.repository.jpa;

import com.rnd.chain.repository.entity.TaskStateHistoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

public interface SpringDataTaskStateHistoryJpaRepository extends JpaRepository<TaskStateHistoryEntity, UUID> {

    /** {@code from <= createdAt < to}. */
    Page<TaskStateHistoryEntity> findByTaskIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            UUID taskId, Instant from, Instant to, Pageable pageable);

    /** Native insert so the DB assigns {@code sequence}; JPA's save() would merge-select first. */
    @Modifying
    @Query(value = """
        INSERT INTO task_state_history (id, task_id, tenant_id, event_type, from_status, to_status,
                                        blocking_count_before, blocking_count_after, created_at)
        VALUES (:id, :taskId, (SELECT tenant_id FROM task WHERE id = :taskId), :eventType, :fromStatus,
                :toStatus, :countBefore, :countAfter, :createdAt)
        """, nativeQuery = true)
    void insert(@Param("id") UUID id, @Param("taskId") UUID taskId, @Param("eventType") String eventType,
            @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
            @Param("countBefore") Integer countBefore, @Param("countAfter") Integer countAfter,
            @Param("createdAt") Instant createdAt);
    
    @Query(value = """
        SELECT DISTINCT ON (task_id)
               task_id,
               created_at
        FROM task_state_history
        WHERE task_id IN (:taskIds)
          AND event_type = 'BLOCKED'
        ORDER BY task_id, created_at DESC, sequence DESC
        """, nativeQuery = true)
    List<Object[]> findLatestBlockedSinceByTaskIds(
        @Param("taskIds") List<UUID> taskIds);

    /** Keyset page: {@code from <= createdAt < to}, strictly after the cursor, oldest first. */
    @Query(value = """
        SELECT * FROM task_state_history
        WHERE tenant_id = :tenantId
          AND created_at >= :from AND created_at < :to
          AND event_type IN (:eventTypes)
          AND (created_at, sequence) > (:cursorTime, :cursorSequence)
        ORDER BY created_at, sequence
        LIMIT :limit
        """, nativeQuery = true)
    List<TaskStateHistoryEntity> findTenantPage(
            @Param("tenantId") UUID tenantId, @Param("from") Instant from, @Param("to") Instant to,
            @Param("eventTypes") List<String> eventTypes, @Param("cursorTime") Instant cursorTime,
            @Param("cursorSequence") long cursorSequence, @Param("limit") int limit);

    /** Tasks whose latest BLOCKED/UNBLOCKED event is BLOCKED, with that event's time. */
    @Query(value = """
        SELECT task_id, created_at FROM (
            SELECT DISTINCT ON (task_id) task_id, event_type, created_at
            FROM task_state_history
            WHERE tenant_id = :tenantId AND event_type IN ('BLOCKED', 'UNBLOCKED')
            ORDER BY task_id, created_at DESC, sequence DESC
        ) latest
        WHERE event_type = 'BLOCKED'
        """, nativeQuery = true)
    List<Object[]> findCurrentlyBlockedSinceByTenant(@Param("tenantId") UUID tenantId);

    @Query(value = """
        SELECT task_id, created_at FROM (
            SELECT DISTINCT ON (task_id) task_id, event_type, created_at
            FROM task_state_history
            WHERE tenant_id = :tenantId AND task_id IN (:taskIds)
              AND event_type IN ('BLOCKED', 'UNBLOCKED')
            ORDER BY task_id, created_at DESC, sequence DESC
        ) latest
        WHERE event_type = 'BLOCKED'
        """, nativeQuery = true)
    List<Object[]> findCurrentlyBlockedSinceByTenantAndTaskIds(
            @Param("tenantId") UUID tenantId, @Param("taskIds") List<UUID> taskIds);
}
