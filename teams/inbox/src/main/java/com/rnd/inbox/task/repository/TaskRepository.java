package com.rnd.inbox.task.repository;
import com.rnd.inbox.task.assignment.Assignment;
import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.model.TaskTier;
import com.rnd.inbox.task.assignment.AssignmentRole;
import com.rnd.inbox.team.member.TeamMember;
import com.rnd.inbox.workinsights.CompletedTaskRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    Optional<Task> findByTenant_IdAndSourceTypeAndSourceRef(
            UUID tenantId, SourceType sourceType, String sourceRef);

    @EntityGraph(attributePaths = "tenant")
    @Query("""
            SELECT t FROM Task t
            WHERE (:bypassTenantScope = TRUE OR t.tenant.id = :tenantId)
              AND (:hasSearch = FALSE
                   OR LOWER(t.title) LIKE :searchPattern
                   OR LOWER(t.description) LIKE :searchPattern)
              AND (:hasStatus = FALSE OR t.status = :status)
              AND (:hasAvailable = FALSE OR t.isAvailable = :available)
              AND (:hasSource = FALSE OR t.sourceType = :source)
              AND (:hasTier = FALSE OR t.tier = :tier)
              AND (:hasDeadlineFrom = FALSE OR t.deadline >= :deadlineFrom)
              AND (:hasDeadlineTo = FALSE OR t.deadline <= :deadlineTo)
              AND (:hasNoDeadline = FALSE OR t.deadline IS NULL)
              AND (:hasRelatedUser = FALSE
                   OR t.owner.id = :relatedUserId
                   OR t.id IN (
                       SELECT a.task.id FROM Assignment a
                       WHERE a.user.id = :relatedUserId
                   )
                   OR (t.owner IS NULL
                       AND t.id IN (
                           SELECT ta.task.id FROM Assignment ta
                           WHERE ta.team.id IN (
                                     SELECT tm.team.id FROM TeamMember tm
                                     WHERE tm.user.id = :relatedUserId AND tm.leftAt IS NULL
                                 )
                             AND NOT EXISTS (
                                     SELECT ua.id FROM Assignment ua
                                     WHERE ua.task = ta.task AND ua.user IS NOT NULL
                                 )
                       )))
            """)
    Page<Task> findFiltered(
            @Param("bypassTenantScope") boolean bypassTenantScope,
            @Param("tenantId") UUID tenantId,
            @Param("hasSearch") boolean hasSearch,
            @Param("searchPattern") String searchPattern,
            @Param("hasStatus") boolean hasStatus,
            @Param("status") TaskStatus status,
            @Param("hasAvailable") boolean hasAvailable,
            @Param("available") Boolean available,
            @Param("hasSource") boolean hasSource,
            @Param("source") SourceType source,
            @Param("hasTier") boolean hasTier,
            @Param("tier") TaskTier tier,
            @Param("hasDeadlineFrom") boolean hasDeadlineFrom,
            @Param("deadlineFrom") Instant deadlineFrom,
            @Param("hasDeadlineTo") boolean hasDeadlineTo,
            @Param("deadlineTo") Instant deadlineTo,
            @Param("hasNoDeadline") boolean hasNoDeadline,
            @Param("hasRelatedUser") boolean hasRelatedUser,
            @Param("relatedUserId") UUID relatedUserId,
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

    String BELONGS_TO_USER = """
            (t.owner.id = :userId
             OR t.id IN (
                 SELECT a.task.id FROM Assignment a
                 WHERE a.user.id = :userId AND a.assignmentRole IN :roles
             ))
            """;

    @Query("""
            SELECT new com.rnd.inbox.workinsights.CompletedTaskRow(
                       t.completedAt, t.deadline, t.effortEstimateMinutes)
            FROM Task t
            WHERE t.tenant.id = :tenantId
              AND t.status = :status
              AND t.completedAt >= :from
              AND t.completedAt < :to
              AND """ + BELONGS_TO_USER)
    List<CompletedTaskRow> findCompletedForUser(@Param("tenantId") UUID tenantId,
                                                @Param("userId") UUID userId,
                                                @Param("roles") Collection<AssignmentRole> roles,
                                                @Param("status") TaskStatus status,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to);

    @Query("""
            SELECT t.deadline FROM Task t
            WHERE t.tenant.id = :tenantId
              AND t.status IN :openStatuses
              AND """ + BELONGS_TO_USER)
    List<Instant> findOpenDeadlinesForUser(@Param("tenantId") UUID tenantId,
                                           @Param("userId") UUID userId,
                                           @Param("roles") Collection<AssignmentRole> roles,
                                           @Param("openStatuses") Collection<TaskStatus> openStatuses);
}
