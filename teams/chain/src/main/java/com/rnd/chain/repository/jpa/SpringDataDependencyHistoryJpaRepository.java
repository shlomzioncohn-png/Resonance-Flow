package com.rnd.chain.repository.jpa;

import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.repository.entity.DependencyHistoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataDependencyHistoryJpaRepository extends JpaRepository<DependencyHistoryEntity, UUID> {

    Optional<DependencyHistoryEntity> findBySourceAndSourceRef(DependencyChangeSource source, String sourceRef);

    Page<DependencyHistoryEntity> findByTaskId(UUID taskId, Pageable pageable);

    /** Native insert; no-ops on a (source, sourceRef) collision instead of throwing. */
    @Modifying
    @Query(value = """
        INSERT INTO dependency_history (id, task_id, depends_on_task_id, action, source, source_ref, created_at)
        VALUES (:id, :taskId, :dependsOnTaskId, :action, :source, :sourceRef, :createdAt)
        ON CONFLICT (source, source_ref) WHERE source_ref IS NOT NULL DO NOTHING
        """, nativeQuery = true)
    void insertIgnoringConflict(@Param("id") UUID id, @Param("taskId") UUID taskId,
            @Param("dependsOnTaskId") UUID dependsOnTaskId, @Param("action") String action,
            @Param("source") String source, @Param("sourceRef") String sourceRef,
            @Param("createdAt") Instant createdAt);
}
