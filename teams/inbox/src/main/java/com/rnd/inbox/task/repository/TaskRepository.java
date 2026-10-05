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
    //
    // searchPattern is already "%lowercased-search%" by the time it gets here (built in
    // TaskService), not assembled in SQL via CONCAT/'%'||param||'%'. Discovered by an integration
    // test against real Postgres: Hibernate compiles JPQL CONCAT to Postgres's `||` operator,
    // and `'%' || ? || '%'` left Postgres unable to infer the parameter's type, resolving it to
    // bytea instead of text ("function lower(bytea) does not exist"). A query that's never
    // actually run against a real DB can't catch this - it parses and validates fine, only live
    // SQL execution reveals Postgres's own operator type-inference failing on it.
    //
    // Every optional filter needs a plain boolean "has-value" flag (like bypassTenantScope)
    // instead of its own bare "$n IS NULL" check - including hasSearch, which looked safe in
    // isolation but wasn't: pgjdbc only switches a query from the simple protocol (values
    // inlined as literals, no type inference needed) to a server-side prepared statement (which
    // requires Postgres to infer each parameter's type up front) after ~5 executions of the same
    // SQL on one connection. A test run alone never crosses that threshold; the full suite does,
    // and only then does "? IS NULL" with no other type context in the statement fail with
    // "could not determine data type of parameter $n". Every field here gets the same safe
    // has-flag pattern uniformly rather than trusting any of them to be a genuine exception.
    //
    // relatedUserId narrows to tasks "related to" one user:
    //   1. tasks they own (tasks.owner_id), or
    //   2. tasks assigned to them personally, or
    //   3. tasks assigned to a team they are currently a member of (left_at IS NULL) - but only
    //      while nobody is personally responsible for the task: no owner and no assignment to an
    //      individual user. Once a specific person holds the task, the rest of the team no
    //      longer sees it as theirs (that person still gets it through rule 1 or 2).
    // Rule 3 is deliberately narrower than findCandidateTasksForUser, which counts every task of
    // the user's teams. The whole block is ANDed with the tenant scope like every other filter,
    // never a replacement for it, so asking for a user from another tenant matches nothing.
    // EXISTS (not a JOIN) keeps one row per task, so the page content and the derived count
    // query stay correct without DISTINCT.
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
                   OR EXISTS (
                       SELECT a.id FROM Assignment a
                       WHERE a.task = t AND a.user.id = :relatedUserId
                   )
                   OR (t.owner IS NULL
                       AND EXISTS (
                           SELECT ta.id FROM Assignment ta
                           WHERE ta.task = t
                             AND ta.team.id IN (
                                 SELECT tm.team.id FROM TeamMember tm
                                 WHERE tm.user.id = :relatedUserId AND tm.leftAt IS NULL
                             )
                       )
                       AND NOT EXISTS (
                           SELECT ua.id FROM Assignment ua
                           WHERE ua.task = t AND ua.user IS NOT NULL
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
}
