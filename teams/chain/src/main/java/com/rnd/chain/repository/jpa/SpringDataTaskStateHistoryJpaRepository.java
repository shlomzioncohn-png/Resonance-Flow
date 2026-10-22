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
        INSERT INTO task_state_history (id, task_id, event_type, from_status, to_status,
                                        blocking_count_before, blocking_count_after, created_at)
        VALUES (:id, :taskId, :eventType, :fromStatus, :toStatus, :countBefore, :countAfter, :createdAt)
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
}
