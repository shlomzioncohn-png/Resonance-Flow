package com.rnd.bff.service;

import com.rnd.bff.client.InboxClient;
import com.rnd.bff.dto.ManagerKpisDTO;
import com.rnd.bff.dto.ManagerProjectDTO;
import com.rnd.bff.dto.ProjectTaskCountsDTO;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.exception.InboxRejectedException;
import com.rnd.bff.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Manager Dashboard's KPI tiles, counted in the BFF from the tenant's
 * Inbox tasks (Inbox has no count endpoint, and no project filter on its task list).
 *
 * - inProgress: status IN_PROGRESS.
 * - blocked: an open task Chain marks as not available (is_available=false) -
 *   Inbox has no BLOCKED status.
 * - delayed: an open task whose deadline day is before today.
 * - avgBlockedDays: needs blocked-since data from Chain, not available yet - always null.
 *
 * "Open" means any status except DONE, REJECTED and CANCELLED.
 */
@Service
public class ManagerKpisService {

    private static final Logger logger = LoggerFactory.getLogger(ManagerKpisService.class);

    /**
     * Accepted and validated, but not applied yet: every KPI is a count of
     * "right now". What a period should change gets decided together with the
     * period filter in the UI.
     */
    static final Set<String> PERIODS = Set.of("7d", "30d", "90d");

    private static final Set<String> CLOSED_STATUSES = Set.of("DONE", "REJECTED", "CANCELLED");

    /** The chart row for the tasks that have no project. */
    static final String NO_PROJECT = "No project";

    /** Inbox sends deadlines as display dates (inbox-team PR #591): "12/10/2026", Israel time. */
    private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final ZoneId ZONE = ZoneId.of("Asia/Jerusalem");

    private final InboxClient inboxClient;
    private final Clock clock;

    @Autowired
    public ManagerKpisService(InboxClient inboxClient) {
        this(inboxClient, Clock.system(ZONE));
    }

    ManagerKpisService(InboxClient inboxClient, Clock clock) {
        this.inboxClient = inboxClient;
        this.clock = clock;
    }

    /**
     * @param userId  the acting manager (X-User-Id); Inbox scopes the tasks to their tenant
     * @param period  7d / 30d / 90d, or null - validated, not applied yet (see PERIODS)
     * @param project an Inbox project id, or null for the whole tenant
     */
    public ManagerKpisDTO getKpis(String userId, String period, String project) {
        if (period != null && !PERIODS.contains(period)) {
            throw new IllegalArgumentException("period must be one of 7d, 30d, 90d");
        }
        List<TaskDTO> tasks = tenantTasks(userId);
        ManagerKpisDTO kpis = count(tasks, project, LocalDate.now(clock));
        logger.info("Manager KPIs - project: {}, period: {}, tasks: {}, kpis: {}",
                project, period, tasks.size(), kpis);
        return kpis;
    }

    /**
     * The Dashboard's charts: the tenant's tasks counted per project, A-Z.
     * Counted from the same tasks as the KPI tiles, so the numbers agree.
     *
     * @param userId the acting manager (X-User-Id); Inbox scopes the tasks to their tenant
     */
    public List<ProjectTaskCountsDTO> getProjectTaskCounts(String userId) {
        List<TaskDTO> tasks = tenantTasks(userId);
        List<ProjectTaskCountsDTO> projects = countByProject(tasks, LocalDate.now(clock));
        logger.info("Manager project task counts - tasks: {}, projects: {}", tasks.size(), projects.size());
        return projects;
    }

    /**
     * The Projects screen, counted from the tenant's tasks (the running Inbox
     * has no project-summary endpoint). One card per project that has tasks;
     * tasks with no project aren't a project, so they're left out here.
     * Team, owner and members aren't in the task data yet - "No team" /
     * "Unassigned" / none until Inbox's project API is available.
     */
    public List<ManagerProjectDTO> getProjects(String userId) {
        List<ManagerProjectDTO> projects = projectCards(tenantTasks(userId), LocalDate.now(clock));
        logger.info("Manager projects - projects: {}", projects.size());
        return projects;
    }

    /** Shown on the card while Inbox doesn't give the project's team / owner. */
    static final String NO_TEAM = "No team";
    static final String UNASSIGNED = "Unassigned";

    /**
     * Health = the share of a project's open tasks that are blocked or overdue.
     * The cutoffs are a judgment call, not a product decision - confirm with
     * the product owner before relying on them.
     */
    static final double AT_RISK_SHARE = 0.25;
    static final double CRITICAL_SHARE = 0.5;

    /** Inbox's display date-time ("04/10/2026 14:01", Israel time). */
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** The counting itself, separate from fetching so it can be tested on its own. */
    static List<ManagerProjectDTO> projectCards(List<TaskDTO> tasks, LocalDate today) {
        Map<String, List<TaskDTO>> byProject = new HashMap<>();
        for (TaskDTO task : tasks) {
            String status = statusOf(task);
            if (task.getProjectId() == null || "REJECTED".equals(status) || "CANCELLED".equals(status)) {
                continue;
            }
            byProject.computeIfAbsent(task.getProjectId(), k -> new ArrayList<>()).add(task);
        }

        List<ManagerProjectDTO> projects = new ArrayList<>();
        for (List<TaskDTO> projectTasks : byProject.values()) {
            int total = projectTasks.size();
            int done = 0;
            int inProgress = 0;
            int blocked = 0;
            int atRisk = 0;
            LocalDateTime lastUpdated = null;
            for (TaskDTO task : projectTasks) {
                String status = statusOf(task);
                if ("DONE".equals(status)) {
                    done++;
                } else {
                    boolean isBlocked = Boolean.FALSE.equals(task.getIsAvailable());
                    if (isBlocked) {
                        blocked++;
                    }
                    if (isBlocked || isPastDeadline(task, today)) {
                        atRisk++;
                    }
                }
                if ("IN_PROGRESS".equals(status)) {
                    inProgress++;
                }
                LocalDateTime updated = dateTimeOf(task.getUpdatedAt());
                if (updated != null && (lastUpdated == null || updated.isAfter(lastUpdated))) {
                    lastUpdated = updated;
                }
            }
            int open = total - done;
            String health = open == 0 || (double) atRisk / open < AT_RISK_SHARE ? "healthy"
                    : (double) atRisk / open < CRITICAL_SHARE ? "warning" : "critical";
            TaskDTO first = projectTasks.get(0);
            projects.add(ManagerProjectDTO.builder()
                    .id(first.getProjectId())
                    .name(first.getProject() == null || first.getProject().isBlank() ? first.getProjectId() : first.getProject())
                    .team(NO_TEAM)
                    .owner(UNASSIGNED)
                    .status(health)
                    .statusLabel(switch (health) {
                        case "healthy" -> "Healthy";
                        case "warning" -> "At Risk";
                        default -> "Critical";
                    })
                    .members(List.of())
                    .progressPercent((int) Math.round(100.0 * done / total))
                    .totalCount(total)
                    .inProgressCount(inProgress)
                    .blockedCount(blocked)
                    .lastUpdatedAt(lastUpdated == null ? null : lastUpdated.atZone(ZONE).toInstant().toString())
                    .build());
        }
        projects.sort(Comparator.comparing(ManagerProjectDTO::getName, String.CASE_INSENSITIVE_ORDER));
        return projects;
    }

    /** null when missing or not Inbox's display format. */
    private static LocalDateTime dateTimeOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), DATE_TIME_FORMAT);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    /** The lists behind the manager's task screens. */
    public enum TaskView {
        /** Work in progress: status IN_PROGRESS. */
        IN_PROGRESS,
        /** Blocked & delayed: open tasks that are blocked (Chain) or past their deadline. */
        BLOCKED_OR_DELAYED
    }

    /**
     * The tenant's tasks for one of the manager's task screens, soonest
     * deadline first (no deadline last). Counted the same way as the KPI
     * tiles, so the list length matches the tile.
     */
    public List<TaskDTO> getTasks(String userId, TaskView view) {
        List<TaskDTO> tasks = filterTasks(tenantTasks(userId), view, LocalDate.now(clock));
        logger.info("Manager task list - view: {}, tasks: {}", view, tasks.size());
        return tasks;
    }

    /** The filtering itself, separate from fetching so it can be tested on its own. */
    static List<TaskDTO> filterTasks(List<TaskDTO> tasks, TaskView view, LocalDate today) {
        List<TaskDTO> matching = new ArrayList<>();
        for (TaskDTO task : tasks) {
            String status = statusOf(task);
            boolean keep = switch (view) {
                case IN_PROGRESS -> "IN_PROGRESS".equals(status);
                case BLOCKED_OR_DELAYED -> !CLOSED_STATUSES.contains(status)
                        && (Boolean.FALSE.equals(task.getIsAvailable()) || isPastDeadline(task, today));
            };
            if (keep) {
                matching.add(task);
            }
        }
        matching.sort(Comparator.comparing(ManagerKpisService::deadlineOf,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return matching;
    }

    private static boolean isPastDeadline(TaskDTO task, LocalDate today) {
        LocalDate deadline = deadlineOf(task);
        return deadline != null && deadline.isBefore(today);
    }

    private List<TaskDTO> tenantTasks(String userId) {
        try {
            return inboxClient.getTenantTasks(userId);
        } catch (InboxRejectedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException("Inbox", "Inbox is unavailable", ex);
        }
    }

    /** The counting itself, separate from fetching so it can be tested on its own. */
    static List<ProjectTaskCountsDTO> countByProject(List<TaskDTO> tasks, LocalDate today) {
        Map<String, ProjectTaskCountsDTO> byProject = new HashMap<>();
        for (TaskDTO task : tasks) {
            String status = statusOf(task);
            if ("REJECTED".equals(status) || "CANCELLED".equals(status)) {
                continue;
            }
            String key = task.getProjectId() == null ? "" : task.getProjectId();
            ProjectTaskCountsDTO counts = byProject.computeIfAbsent(key, k -> ProjectTaskCountsDTO.builder()
                    .projectId(task.getProjectId())
                    .project(task.getProject() == null || task.getProject().isBlank()
                            ? NO_PROJECT : task.getProject())
                    .build());
            switch (status) {
                case "IN_PROGRESS" -> counts.setInProgress(counts.getInProgress() + 1);
                case "SNOOZED" -> counts.setSnoozed(counts.getSnoozed() + 1);
                case "DONE" -> counts.setDone(counts.getDone() + 1);
                // NEW, IN_REVIEW, APPROVED
                default -> counts.setOpen(counts.getOpen() + 1);
            }
            LocalDate deadline = deadlineOf(task);
            if (!"DONE".equals(status) && deadline != null && deadline.isBefore(today)) {
                counts.setOverdue(counts.getOverdue() + 1);
            }
        }
        List<ProjectTaskCountsDTO> projects = new ArrayList<>(byProject.values());
        // A-Z, with the "No project" row last
        projects.sort(Comparator.comparing((ProjectTaskCountsDTO p) -> p.getProjectId() == null)
                .thenComparing(ProjectTaskCountsDTO::getProject, String.CASE_INSENSITIVE_ORDER));
        return projects;
    }

    /** The counting itself, separate from fetching so it can be tested on its own. */
    static ManagerKpisDTO count(List<TaskDTO> tasks, String project, LocalDate today) {
        int inProgress = 0;
        int blocked = 0;
        int delayed = 0;
        for (TaskDTO task : tasks) {
            if (project != null && !project.equals(task.getProjectId())) {
                continue;
            }
            String status = statusOf(task);
            if ("IN_PROGRESS".equals(status)) {
                inProgress++;
            }
            if (CLOSED_STATUSES.contains(status)) {
                continue;
            }
            if (Boolean.FALSE.equals(task.getIsAvailable())) {
                blocked++;
            }
            LocalDate deadline = deadlineOf(task);
            if (deadline != null && deadline.isBefore(today)) {
                delayed++;
            }
        }
        return ManagerKpisDTO.builder()
                .inProgress(inProgress)
                .blocked(blocked)
                .delayed(delayed)
                .avgBlockedDays(null)
                .build();
    }

    private static String statusOf(TaskDTO task) {
        String status = task.getInboxStatus() != null ? task.getInboxStatus() : task.getStatus();
        return status == null ? "" : status.toUpperCase();
    }

    /** null when there's no deadline, or it isn't a date we can read (logged, not counted). */
    private static LocalDate deadlineOf(TaskDTO task) {
        String dueDate = task.getDueDate();
        if (dueDate == null || dueDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dueDate.trim(), DEADLINE_FORMAT);
        } catch (DateTimeParseException ex) {
            logger.warn("Task {} has an unreadable deadline '{}' - not counted as delayed", task.getId(), dueDate);
            return null;
        }
    }
}
