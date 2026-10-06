package com.rnd.bff.service;

import com.rnd.bff.client.ChainClient;
import com.rnd.bff.client.EmployeeClient;
import com.rnd.bff.client.InboxClient;
import com.rnd.bff.client.InboxSelectionResponse;
import com.rnd.bff.client.MindClient;
import com.rnd.bff.dto.EmployeeTaskDTO;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.dto.TaskDependenciesDTO;
import com.rnd.bff.dto.TaskListResponseDTO;
import com.rnd.bff.dto.SelectedTaskDTO;
import com.rnd.bff.dto.TaskRecommendationDTO;
import com.rnd.bff.dto.TaskSelectionDTO;
import com.rnd.bff.dto.WorkspaceRequestDTO;
import com.rnd.bff.exception.InboxRejectedException;
import com.rnd.bff.exception.ServiceUnavailableException;
import com.rnd.bff.exception.TaskNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates Inbox (task data), Chain (dependencies/feasibility), and Mind
 * (scoring) into a single task recommendation. All three are mocked at the
 * client level until those services expose real APIs — see
 * docs/workspace-screen-integration-plan.md, "Work Plan by Layer".
 */
@Service
public class WorkspaceService {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceService.class);

    private final InboxClient inboxClient;
    private final ChainClient chainClient;
    private final MindClient mindClient;
    private final EmployeeClient employeeClient;

    public WorkspaceService(
            InboxClient inboxClient,
            ChainClient chainClient,
            MindClient mindClient,
            EmployeeClient employeeClient) {
        this.inboxClient = inboxClient;
        this.chainClient = chainClient;
        this.mindClient = mindClient;
        this.employeeClient = employeeClient;
    }

    public TaskRecommendationDTO recommend(WorkspaceRequestDTO request, String userId) {
        logger.info(
                "Workspace recommendation request - userId: {}, availableTime: {} minutes",
                userId,
                request.getAvailableTime());

        // Real Inbox: Inbox picks the tasks itself (its own effort estimates,
        // scoring and "what fits together in N minutes"). If that call fails
        // for any reason other than Inbox refusing the request, fall back to
        // the BFF's own ranking below - same degrade-don't-fail approach as
        // for Chain and Mind.
        if (!inboxClient.isMockEnabled()) {
            try {
                return recommendFromInboxSelection(request, userId);
            } catch (InboxRejectedException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                logger.warn("Inbox task selection failed, falling back to BFF ranking - userId: {}", userId, ex);
            }
        }

        List<TaskDTO> candidates;
        try {
            candidates = filterCandidates(inboxClient.getTasksForUser(userId), request);
        } catch (InboxRejectedException ex) {
            // Inbox is up and said no (401/403/400) - not an outage, pass it on as-is
            throw ex;
        } catch (RuntimeException ex) {
            // Inbox owns canonical task data - with no task list, no recommendation
            // is possible, so this fails the request rather than degrading.
            throw new ServiceUnavailableException("Inbox", "Inbox is unavailable", ex);
        }
        List<String> candidateIds = candidates.stream().map(TaskDTO::getId).toList();

        Map<String, TaskDependenciesDTO> feasibilityByTaskId;
        boolean chainAvailable = true;
        try {
            feasibilityByTaskId = chainClient.getFeasibility(userId, candidateIds);
        } catch (RuntimeException ex) {
            // Chain is down: a recommendation is still possible, just without
            // dependency data. Treat every task's dependency status as unknown
            // rather than failing the whole request.
            logger.warn("Chain unavailable, proceeding without dependency data - userId: {}", userId, ex);
            feasibilityByTaskId = Map.of();
            chainAvailable = false;
        }

        Map<String, Integer> scoreByTaskId;
        boolean mindAvailable = true;
        try {
            scoreByTaskId = mindClient.getScores(userId, candidateIds, request.getAvailableTime());
        } catch (RuntimeException ex) {
            // Mind is down: a recommendation is still possible, just unranked.
            // Every candidate falls back to the same neutral score below.
            logger.warn("Mind unavailable, proceeding without scores - userId: {}", userId, ex);
            scoreByTaskId = Map.of();
            mindAvailable = false;
        }

        for (TaskDTO task : candidates) {
            // Chain may not have an entry for every task even when it's up -
            // it's a partial map, not an edge case - so dependencies/constraints
            // stay null (unknown) rather than defaulting to "none".
            TaskDependenciesDTO feasibility = feasibilityByTaskId.get(task.getId());
            if (feasibility != null) {
                task.setDependencies(feasibility.getDependencies());
                task.setConstraints(feasibility.getConstraints());
            }
        }

        // Mind's score decides. On a tie (e.g. Mind's mock scores every task the
        // same), prefer a task that fits the available time, then the more
        // important one - otherwise the first task in Inbox's list would win.
        Map<String, Integer> finalScoreByTaskId = scoreByTaskId;
        int availableTime = request.getAvailableTime();
        TaskDTO bestTask =
                candidates.stream()
                        .max(Comparator.<TaskDTO, Integer>comparing(
                                        task -> finalScoreByTaskId.getOrDefault(task.getId(), 0))
                                .thenComparing(task -> fitsIn(task, availableTime))
                                .thenComparing(task -> task.getPriorityScore() == null ? 0.0 : task.getPriorityScore()))
                        .orElse(null);

        if (bestTask == null) {
            return noMatchingTasks("No matching tasks available right now.");
        }

        // Unknown effort -> false (see fitsIn), matching the unknown-dependencies handling below.
        boolean fitsInTime = fitsIn(bestTask, availableTime);
        boolean isHighPriority = "HIGH".equals(bestTask.getPriority());
        // "No dependencies" requires Chain to have actually said so; unknown
        // (Chain down, or no entry for this task) is not the same as "none".
        boolean hasNoDependencies =
                chainAvailable
                        && bestTask.getDependencies() != null
                        && bestTask.getDependencies().isEmpty();

        List<TaskDTO> alternatives = new ArrayList<>(candidates);
        alternatives.remove(bestTask);

        return TaskRecommendationDTO.builder()
                .task(bestTask)
                .explanation(
                        buildExplanation(
                                request, fitsInTime, isHighPriority, hasNoDependencies, chainAvailable, mindAvailable))
                .fitsInTime(fitsInTime)
                .isHighPriority(isHighPriority)
                .hasNoDependencies(hasNoDependencies)
                .confidenceScore(scoreByTaskId.getOrDefault(bestTask.getId(), 0))
                .alternatives(alternatives)
                .build();
    }

    /**
     * Builds the recommendation from Inbox's task selection: the first selected
     * task is the recommendation (fetched in full, so the card has status,
     * priority etc.), the rest are alternatives in Inbox's suggested order.
     * Note: projectFilter/minPriority from the request aren't applied here -
     * Inbox's selection doesn't take them.
     */
    private TaskRecommendationDTO recommendFromInboxSelection(WorkspaceRequestDTO request, String userId) {
        InboxSelectionResponse selection = inboxClient.selectTasks(userId, request.getAvailableTime());
        List<InboxSelectionResponse.SelectedTask> selected =
                selection.getSelectedTasks() == null ? List.of() : selection.getSelectedTasks();
        String reason = selection.getReason();
        boolean hasReason = reason != null && !reason.isBlank();
        if (selected.isEmpty()) {
            return noMatchingTasks(hasReason ? reason : "No matching tasks available right now.");
        }

        InboxSelectionResponse.SelectedTask first = selected.get(0);
        TaskDTO bestTask = inboxClient.getTaskById(userId, first.getId());
        if (bestTask == null) {
            bestTask = toTaskDTO(first);
        }
        List<TaskDTO> alternatives = selected.stream().skip(1).map(WorkspaceService::toTaskDTO).toList();

        return TaskRecommendationDTO.builder()
                .task(bestTask)
                .explanation(hasReason ? reason : "Picked to fit your " + request.getAvailableTime() + " minutes")
                // usedFallback: nothing fit, so Inbox widened the window
                .fitsInTime(!selection.isUsedFallback())
                .isHighPriority("HIGH".equals(bestTask.getPriority()))
                // Inbox only selects tasks that aren't blocked (is_available)
                .hasNoDependencies(Boolean.TRUE.equals(bestTask.getIsAvailable()))
                // selection_score is 0-1; the card shows it as "N% match"
                .confidenceScore((int) Math.round(first.getSelectionScore() * 100))
                .alternatives(alternatives)
                .build();
    }

    /** A selected task as TaskDTO, when the full task isn't fetched (alternatives). */
    private static TaskDTO toTaskDTO(InboxSelectionResponse.SelectedTask task) {
        return TaskDTO.builder()
                .id(task.getId())
                .title(task.getTitle())
                .description(task.getDescription())
                .effortEstimate(task.getEffortEstimateMinutes())
                .dueDate(task.getDeadline())
                .build();
    }

    private static TaskRecommendationDTO noMatchingTasks(String explanation) {
        return TaskRecommendationDTO.builder()
                .explanation(explanation)
                .fitsInTime(false)
                .isHighPriority(false)
                .hasNoDependencies(true)
                .confidenceScore(0)
                .alternatives(List.of())
                .build();
    }

    /**
     * Statuses a task can still be worked on in (see
     * docs/employee-workspace-api-contract.md §5.0). DONE, REJECTED and
     * CANCELLED are closed; SNOOZED is paused on purpose.
     */
    private static final List<String> WORKABLE_STATUSES = List.of("NEW", "IN_REVIEW", "APPROVED", "IN_PROGRESS");

    /** An unknown effort estimate can't be confirmed to fit - false is the conservative read. */
    private static boolean fitsIn(TaskDTO task, int availableTime) {
        return task.getEffortEstimate() != null && task.getEffortEstimate() <= availableTime;
    }

    private List<TaskDTO> filterCandidates(List<TaskDTO> tasks, WorkspaceRequestDTO request) {
        return tasks.stream()
                .filter(t -> WORKABLE_STATUSES.contains(t.getStatus()))
                // isAvailable=false means blocked (by dependencies/constraints, set by Chain);
                // null means unknown, which isn't treated as blocked
                .filter(t -> !Boolean.FALSE.equals(t.getIsAvailable()))
                .filter(
                        t ->
                                request.getProjectFilter() == null
                                        || request.getProjectFilter().equals(t.getProject()))
                .filter(
                        t ->
                                request.getMinPriority() == null
                                        || meetsMinPriority(t.getPriority(), request.getMinPriority()))
                .toList();
    }

    private boolean meetsMinPriority(String taskPriority, String minPriority) {
        List<String> order = List.of("LOW", "MEDIUM", "HIGH");
        return order.indexOf(taskPriority) >= order.indexOf(minPriority);
    }

    private String buildExplanation(
            WorkspaceRequestDTO request,
            boolean fitsInTime,
            boolean isHighPriority,
            boolean hasNoDependencies,
            boolean chainAvailable,
            boolean mindAvailable) {
        List<String> reasons = new ArrayList<>();
        reasons.add(
                fitsInTime
                        ? "Fits in your " + request.getAvailableTime() + " minutes"
                        : "Does not fit in your " + request.getAvailableTime() + " minutes");
        if (isHighPriority) {
            reasons.add("high importance");
        }
        if (hasNoDependencies) {
            reasons.add("no blocking dependencies");
        }
        if (!chainAvailable) {
            reasons.add("dependency data unavailable");
        }
        if (!mindAvailable) {
            reasons.add("ranking unavailable, showing default order");
        }
        return String.join(", ", reasons);
    }

    /**
     * "My Tasks" screen: the employee's task list, with status filter, search,
     * and pagination - all three passed through to Inbox, which pages for us.
     *
     * filter values: all | one of the 8 statuses in lowercase (default all for
     * null/blank/unrecognized - fail open rather than hide tasks behind a
     * typo'd filter value).
     *
     * KNOWN GAP: Inbox returns every task in the employee's tenant, not only
     * their own - Inbox has no owner/assignee filter yet. Raised with Inbox.
     */
    public TaskListResponseDTO listTasks(String userId, String filter, String search, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);

        try {
            return inboxClient.listTasks(userId, toStatusFilter(filter), search, safePage, safeSize);
        } catch (InboxRejectedException ex) {
            // Inbox is up and said no (401/403/400) - not an outage, pass it on as-is
            throw ex;
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException("Inbox", "Inbox is unavailable", ex);
        }
    }

    private static final List<String> STATUSES = List.of(
            "NEW", "IN_REVIEW", "APPROVED", "IN_PROGRESS", "SNOOZED", "DONE", "REJECTED", "CANCELLED");

    /**
     * The frontend's filter is one of the 8 statuses in lowercase, or "all".
     * Anything else (blank, "all", an unknown value) means no status filter.
     */
    private static String toStatusFilter(String filter) {
        if (filter == null) {
            return null;
        }
        String status = filter.toUpperCase();
        return STATUSES.contains(status) ? status : null;
    }

    /** Inbox's limit for one selection window (TaskSelectionRequestDto.MAX_AVAILABLE_MINUTES). */
    public static final int MAX_SELECTION_MINUTES = 480;

    /**
     * "Which tasks should I do in N minutes?" - Inbox picks the tasks that fit
     * together (its own effort estimates and scoring). Unlike recommend(),
     * this returns Inbox's whole selection as-is, with no BFF ranking or
     * fallback: if Inbox can't answer, it's reported as unavailable.
     *
     * @throws IllegalArgumentException (-> 400) if availableMinutes is missing or not 1-480
     */
    public TaskSelectionDTO selectTasks(String userId, Integer availableMinutes) {
        if (availableMinutes == null || availableMinutes < 1 || availableMinutes > MAX_SELECTION_MINUTES) {
            throw new IllegalArgumentException(
                    "availableMinutes must be between 1 and " + MAX_SELECTION_MINUTES);
        }
        InboxSelectionResponse selection;
        try {
            selection = inboxClient.selectTasks(userId, availableMinutes);
        } catch (InboxRejectedException ex) {
            // Inbox is up and said no (401/403/400) - not an outage, pass it on as-is
            throw ex;
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException("Inbox", "Inbox is unavailable", ex);
        }
        List<SelectedTaskDTO> tasks = selection.getSelectedTasks() == null
                ? List.of()
                : selection.getSelectedTasks().stream()
                        .map(task -> SelectedTaskDTO.builder()
                                .id(task.getId())
                                .title(task.getTitle())
                                .description(task.getDescription())
                                .effortEstimate(task.getEffortEstimateMinutes())
                                .dueDate(task.getDeadline())
                                .selectionScore(task.getSelectionScore())
                                .build())
                        .toList();
        return TaskSelectionDTO.builder()
                .availableMinutes(availableMinutes)
                .selectedTasks(tasks)
                .totalEffortMinutes(selection.getTotalEffortMinutes())
                .remainingMinutes(selection.getRemainingMinutes())
                .usedFallback(selection.isUsedFallback())
                .reason(selection.getReason())
                .build();
    }

    /** Task details panel / drill-down from either My Workspace or My Tasks. */
    public TaskDTO getTask(String userId, String taskId) {
        TaskDTO task;
        try {
            task = inboxClient.getTaskById(userId, taskId);
        } catch (InboxRejectedException ex) {
            // Inbox is up and said no (401/403/400) - not an outage, pass it on as-is
            throw ex;
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException("Inbox", "Inbox is unavailable", ex);
        }
        if (task == null) {
            throw new TaskNotFoundException(taskId);
        }
        return task;
    }

    /** "Start task" / manual status changes from the task details panel. */
    public TaskDTO updateTaskStatus(String userId, String taskId, String newStatus, Instant snoozedUntil) {
        TaskDTO updated;
        try {
            updated = inboxClient.updateStatus(userId, taskId, newStatus, snoozedUntil);
        } catch (InboxRejectedException ex) {
            // Inbox is up and said no (401/403/400) - not an outage, pass it on as-is
            throw ex;
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException("Inbox", "Inbox is unavailable", ex);
        }
        if (updated == null) {
            throw new TaskNotFoundException(taskId);
        }
        return updated;
    }

    /** My Workspace's chat/blocking-dependency demo — see EmployeeClient's javadoc. */
    public EmployeeTaskDTO getMyTask(String userId) {
        return employeeClient.getMyTask(userId);
    }
}
