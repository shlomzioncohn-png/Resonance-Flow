package com.rnd.inbox.project;

import com.rnd.inbox.task.model.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    // Same shape as TaskRepository.findFiltered: every filter is optional, switched on by its own
    // has* flag rather than by a NULL check on the value, and the tenant condition is skipped only
    // for SYSTEM_ADMIN (bypassTenantScope). The entity graph joins tenant, owner and team into the
    // page query - the response shows all three, and without it each row would load them with
    // SELECTs of their own.
    @EntityGraph(attributePaths = {"tenant", "owner", "team"})
    @Query("""
            SELECT p FROM Project p
            WHERE (:bypassTenantScope = TRUE OR p.tenant.id = :tenantId)
              AND (:hasSearch = FALSE OR LOWER(p.name) LIKE :searchPattern)
              AND (:hasStatus = FALSE OR p.status = :status)
              AND (:hasTeam = FALSE OR p.team.id = :teamId)
              AND (:hasOwner = FALSE OR p.owner.id = :ownerId)
            """)
    Page<Project> findFiltered(
            @Param("bypassTenantScope") boolean bypassTenantScope,
            @Param("tenantId") UUID tenantId,
            @Param("hasSearch") boolean hasSearch,
            @Param("searchPattern") String searchPattern,
            @Param("hasStatus") boolean hasStatus,
            @Param("status") ProjectStatus status,
            @Param("hasTeam") boolean hasTeam,
            @Param("teamId") UUID teamId,
            @Param("hasOwner") boolean hasOwner,
            @Param("ownerId") UUID ownerId,
            Pageable pageable);

    @Query("SELECT COUNT(t) FROM Task t WHERE t.project.id = :projectId")
    long countTasksByProjectId(@Param("projectId") UUID projectId);

    // Task counts for one page of projects, in a single GROUP BY - never one query per project.
    // A project without tasks has no row here at all; the caller treats a missing row as zeros.
    // Statuses are bound as parameters instead of written as enum literals in the query, so they
    // go through the same lowercase native-enum mapping as every other task_status value.
    @Query("""
            SELECT t.project.id AS projectId,
                   COUNT(t) AS total,
                   SUM(CASE WHEN t.status IN :openStatuses THEN 1 ELSE 0 END) AS open,
                   SUM(CASE WHEN t.status = :inProgressStatus THEN 1 ELSE 0 END) AS inProgress,
                   SUM(CASE WHEN t.status = :snoozedStatus THEN 1 ELSE 0 END) AS snoozed,
                   SUM(CASE WHEN t.status = :doneStatus THEN 1 ELSE 0 END) AS done,
                   SUM(CASE WHEN t.status IN :closedWithoutDoneStatuses THEN 1 ELSE 0 END) AS closedWithoutDone,
                   SUM(CASE WHEN t.deadline < :now AND t.status NOT IN :finishedStatuses THEN 1 ELSE 0 END)
                       AS overdue,
                   MAX(t.updatedAt) AS lastTaskUpdatedAt
            FROM Task t
            WHERE t.project.id IN :projectIds
            GROUP BY t.project.id
            """)
    List<ProjectTaskStats> findTaskStatsByProjectIds(
            @Param("projectIds") Collection<UUID> projectIds,
            @Param("openStatuses") Collection<TaskStatus> openStatuses,
            @Param("inProgressStatus") TaskStatus inProgressStatus,
            @Param("snoozedStatus") TaskStatus snoozedStatus,
            @Param("doneStatus") TaskStatus doneStatus,
            @Param("closedWithoutDoneStatuses") Collection<TaskStatus> closedWithoutDoneStatuses,
            @Param("finishedStatuses") Collection<TaskStatus> finishedStatuses,
            @Param("now") Instant now);
}
