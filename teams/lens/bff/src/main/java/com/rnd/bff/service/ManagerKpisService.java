package com.rnd.bff.service;

import com.rnd.bff.client.InboxClient;
import com.rnd.bff.dto.ManagerKpisDTO;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.exception.InboxRejectedException;
import com.rnd.bff.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
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
        List<TaskDTO> tasks;
        try {
            tasks = inboxClient.getTenantTasks(userId);
        } catch (InboxRejectedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException("Inbox", "Inbox is unavailable", ex);
        }
        ManagerKpisDTO kpis = count(tasks, project, LocalDate.now(clock));
        logger.info("Manager KPIs - project: {}, period: {}, tasks: {}, kpis: {}",
                project, period, tasks.size(), kpis);
        return kpis;
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
