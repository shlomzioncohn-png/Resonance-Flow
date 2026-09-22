package com.rnd.inbox.taskselection;

import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.repository.TaskRepository;
import com.rnd.inbox.task.model.TaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TaskSelectionService {

    private static final Set<TaskStatus> ALWAYS_BLOCKING_STATUSES =
            EnumSet.of(TaskStatus.DONE, TaskStatus.REJECTED, TaskStatus.CANCELLED);

    private final TaskRepository taskRepository;
    private final EffortEstimateResolver effortEstimateResolver;
    private final SelectionScoreCalculator selectionScoreCalculator;
    private final TaskSelectionKnapsackSolver knapsackSolver;
    private final TaskSelectionMapper mapper;
    private final Clock clock;

    public TaskSelectionService(TaskRepository taskRepository,
                                 EffortEstimateResolver effortEstimateResolver,
                                 SelectionScoreCalculator selectionScoreCalculator,
                                 TaskSelectionKnapsackSolver knapsackSolver,
                                 TaskSelectionMapper mapper,
                                 Clock clock) {
        this.taskRepository = taskRepository;
        this.effortEstimateResolver = effortEstimateResolver;
        this.selectionScoreCalculator = selectionScoreCalculator;
        this.knapsackSolver = knapsackSolver;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TaskSelectionResponseDto selectTasks(UUID tenantId, UUID userId, int availableMinutes) {
        Instant now = Instant.now(clock);

        List<Task> rawCandidates = taskRepository.findCandidateTasksForUser(tenantId, userId);

        List<Task> eligibleTasks = rawCandidates.stream()
                .filter(task -> !isBlocked(task, now))
                .filter(Task::isAvailable)
                .toList();

        if (eligibleTasks.isEmpty()) {
            return mapper.noTasksAvailable(availableMinutes);
        }

        List<Task> windowFiltered = filterByWindow(eligibleTasks, availableMinutes);

        int effectiveAvailableMinutes = availableMinutes;
        boolean usedFallback = false;
        String reason = null;

        if (windowFiltered.isEmpty()) {
            int minEffortMinutes = eligibleTasks.stream()
                    .mapToInt(effortEstimateResolver::resolvedEffortEstimateMinutes)
                    .min()
                    .orElseThrow();

            effectiveAvailableMinutes = minEffortMinutes;
            windowFiltered = filterByWindow(eligibleTasks, effectiveAvailableMinutes);
            usedFallback = true;
            reason = "No task fit the original " + availableMinutes + " minutes; "
                    + "window was automatically expanded to " + minEffortMinutes + " minutes";
        }

        Map<Task, Double> scores = windowFiltered.stream()
                .collect(Collectors.toMap(task -> task, task -> selectionScoreCalculator.calculateSelectionScore(task, now)));

        List<Task> selectedTasks = knapsackSolver.solve(scores, effectiveAvailableMinutes);

        Map<Task, Double> selectedScores = selectedTasks.stream()
                .collect(Collectors.toMap(task -> task, scores::get));

        return mapper.toResponseDto(selectedScores, effectiveAvailableMinutes, usedFallback, reason);
    }

    private boolean isBlocked(Task task, Instant now) {
        if (ALWAYS_BLOCKING_STATUSES.contains(task.getStatus())) {
            return true;
        }
        if (task.getStatus() == TaskStatus.SNOOZED) {
            Instant snoozedUntil = task.getSnoozedUntil();
            return snoozedUntil == null || now.isBefore(snoozedUntil);
        }
        return false;
    }

    private List<Task> filterByWindow(List<Task> tasks, int availableMinutes) {
        return tasks.stream()
                .filter(task -> effortEstimateResolver.resolvedEffortEstimateMinutes(task) <= availableMinutes)
                .toList();
    }
}
