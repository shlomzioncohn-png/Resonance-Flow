package com.rnd.inbox.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    Optional<Task> findByTenant_IdAndSourceTypeAndSourceRef(
            UUID tenantId, SourceType sourceType, String sourceRef);

    @Query("""
            SELECT DISTINCT t FROM Task t
            JOIN Assignment a ON a.task = t
            WHERE t.tenant.id = :tenantId
              AND (a.user.id = :userId
                   OR a.team.id IN (
                       SELECT tm.team.id FROM TeamMember tm
                       WHERE tm.user.id = :userId AND tm.leftAt IS NULL
                   ))
            """)
    List<Task> findCandidateTasksForUser(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);
}
