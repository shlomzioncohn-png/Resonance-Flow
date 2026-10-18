package com.rnd.inbox.taskselection;

import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.shared.security.ActorScope;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.repository.TaskRepository;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.security.TaskAuthorization;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.repository.UserRepository;
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
    private final UserRepository userRepository;
    private final TaskAuthorization taskAuthorization;
    private final EffortEstimateResolver effortEstimateResolver;
    private final SelectionScoreCalculator selectionScoreCalculator;
    private final TaskSelectionKnapsackSolver knapsackSolver;
    private final TaskSelectionMapper mapper;
    private final Clock clock;

    public TaskSelectionService(TaskRepository taskRepository,
                                 UserRepository userRepository,
                                 TaskAuthorization taskAuthorization,
                                 EffortEstimateResolver effortEstimateResolver,
                                 SelectionScoreCalculator selectionScoreCalculator,
                                 TaskSelectionKnapsackSolver knapsackSolver,
                                 TaskSelectionMapper mapper,
                                 Clock clock) {
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
        this.taskAuthorization = taskAuthorization;
        this.effortEstimateResolver = effortEstimateResolver;
        this.selectionScoreCalculator = selectionScoreCalculator;
        this.knapsackSolver = knapsackSolver;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TaskSelectionResponseDto selectTasksForUser(UUID actorId, UUID userId, int availableMinutes) {
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new UnauthenticatedException("Actor " + actorId + " does not exist"));
        UUID targetUserId = userId != null ? userId : actorId;

        UUID tenantId = switch (taskAuthorization.scopeOf(actor)) {
            case ActorScope.WithinTenant(UUID id) -> id;
            case ActorScope.All ignored -> userRepository.findById(targetUserId)
                    .map(target -> target.getTenant().getId())
                    .orElse(null);
            case ActorScope.WithinTeamsLedBy ignored ->
                    throw new ForbiddenActionException("Actor has no valid scope for tasks");
            case ActorScope.SelfOnly ignored ->
                    throw new ForbiddenActionException("Actor has no valid scope for tasks");
        };
        if (tenantId == null) {
            return mapper.noTasksAvailable(availableMinutes);
        }
        return selectTasks(tenantId, targetUserId, availableMinutes);
    }

    @Transactional(readOnly = true)
    public TaskSelectionResponseDto selectTasks(UUID tenantId, UUID userId, int availableMinutes) {
        if (availableMinutes <= 0) {
            return mapper.noTasksAvailable(availableMinutes);
        }

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
