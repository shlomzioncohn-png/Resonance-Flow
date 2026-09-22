package com.rnd.inbox.task.repository;
import com.rnd.inbox.task.assignment.Assignment;
import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.model.TaskTier;
import com.rnd.inbox.team.member.TeamMember;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    Optional<Task> findByTenant_IdAndSourceTypeAndSourceRef(
            UUID tenantId, SourceType sourceType, String sourceRef);

    // Every user-facing filter param is optional (null = "don't filter on this"). tenantId scope
    // is deliberately NOT expressed the same way: bypassTenantScope is a plain boolean, set only
    // from an explicit SYSTEM_ADMIN role check in TaskService (never from a possibly-null field),
    // so a bug that left tenantId null for a non-admin fails closed (matches nothing) instead of
    // silently granting cross-tenant access the way "tenantId IS NULL -> see everything" would.
    // Validated by TaskFilterService before this is called: deadlineFrom/deadlineTo are
    // both-or-neither, and noDeadline (when true) is mutually exclusive with them.
    @Query("""
            SELECT t FROM Task t
            WHERE (:bypassTenantScope = TRUE OR t.tenant.id = :tenantId)
              AND (:search IS NULL
                   OR LOWER(t.title) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(t.description) LIKE LOWER(CONCAT('%', :search, '%')))
              AND (:status IS NULL OR t.status = :status)
              AND (:available IS NULL OR t.available = :available)
              AND (:source IS NULL OR t.sourceType = :source)
              AND (:tier IS NULL OR t.tier = :tier)
              AND (:deadlineFrom IS NULL OR t.deadline >= :deadlineFrom)
              AND (:deadlineTo IS NULL OR t.deadline <= :deadlineTo)
              AND (:noDeadline IS NULL OR :noDeadline = FALSE OR t.deadline IS NULL)
            """)
    Page<Task> findFiltered(
            @Param("bypassTenantScope") boolean bypassTenantScope,
            @Param("tenantId") UUID tenantId,
            @Param("search") String search,
            @Param("status") TaskStatus status,
            @Param("available") Boolean available,
            @Param("source") SourceType source,
            @Param("tier") TaskTier tier,
            @Param("deadlineFrom") Instant deadlineFrom,
            @Param("deadlineTo") Instant deadlineTo,
            @Param("noDeadline") Boolean noDeadline,
            Pageable pageable);

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
