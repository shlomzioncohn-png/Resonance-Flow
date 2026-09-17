package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskEligibilityResponse;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.exception.DependencyNotFoundException;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class TaskEligibilityService {

        private static final Logger log = LoggerFactory.getLogger(TaskEligibilityService.class);

        private final TaskRepository taskRepository;
        private final TaskDependencyRepository taskDependencyRepository;
        private final SchedulingUpdateService schedulingUpdateService;

        private final Object dependencyGraphLock = new Object();

        public TaskEligibilityService(
                        TaskRepository taskRepository,
                        TaskDependencyRepository taskDependencyRepository,
                        SchedulingUpdateService schedulingUpdateService) {

                this.taskRepository = taskRepository;
                this.taskDependencyRepository = taskDependencyRepository;
                this.schedulingUpdateService = schedulingUpdateService;
        }

        private Task requireTask(UUID taskId) {

                return taskRepository.findById(taskId)
                                .orElseThrow(() -> new TaskNotFoundException(taskId));
        }

        private static <T> T requireUpdated(
                        Optional<T> updated,
                        UUID taskId) {

                return updated.orElseThrow(() -> new TaskNotFoundException(taskId));
        }

        private static Task.Builder copyOf(Task task) {

                return Task.builder()
                                .id(task.getId())
                                .parentTaskId(task.getParentTaskId())
                                .ownerId(task.getOwnerId())
                                .status(task.getStatus())
                                .isReady(task.isReady())
                                .blockingCount(task.getBlockingCount())
                                .openConstraintCount(task.getOpenConstraintCount())
                                .estimatedDurationMin(task.getEstimatedDurationMin())
                                .score(task.getScore())
                                .createdAt(task.getCreatedAt())
                                .updatedAt(task.getUpdatedAt())
                                .deletedAt(task.getDeletedAt());
        }

        private static Task withCounters(
                        Task task,
                        int blockingCount,
                        int openConstraintCount) {

                boolean isReady = blockingCount == 0
                                && openConstraintCount == 0;

                if (task.getBlockingCount() == blockingCount
                                && task.getOpenConstraintCount() == openConstraintCount
                                && task.isReady() == isReady) {

                        return task;
                }

                return copyOf(task)
                                .blockingCount(blockingCount)
                                .openConstraintCount(openConstraintCount)
                                .isReady(isReady)
                                .build();
        }

        private Task updateCounters(
                        UUID taskId,
                        UnaryOperator<Task> countersMutator) {

                AtomicReference<Task> previous = new AtomicReference<>();

                Task updated = requireUpdated(
                                taskRepository.update(
                                                taskId,
                                                task -> {
                                                        previous.set(task);
                                                        return countersMutator.apply(task);
                                                }),
                                taskId);

                Task oldTask = previous.get();

                if (updated != oldTask) {

                        log.info(
                                        "Task {} eligibility updated: "
                                                        + "blockingCount={}, "
                                                        + "openConstraintCount={}, "
                                                        + "isReady={}",
                                        taskId,
                                        updated.getBlockingCount(),
                                        updated.getOpenConstraintCount(),
                                        updated.isReady());
                }

                if (oldTask != null
                                && oldTask.isReady() != updated.isReady()) {

                        schedulingUpdateService
                                        .publishSchedulingUpdate(updated);
                }

                return updated;
        }

        public void recalculateTaskEligibility(UUID taskId) {

                Objects.requireNonNull(
                                taskId,
                                "taskId must not be null");

                updateCounters(
                                taskId,
                                task -> withCounters(
                                                task,
                                                task.getBlockingCount(),
                                                task.getOpenConstraintCount()));
        }

        public TaskDependency addDependency(
                        UUID taskId,
                        UUID dependsOnTaskId) {

                Objects.requireNonNull(
                                taskId,
                                "taskId must not be null");

                Objects.requireNonNull(
                                dependsOnTaskId,
                                "dependsOnTaskId must not be null");

                synchronized (dependencyGraphLock) {

                        if (wouldCreateCycle(
                                        taskId,
                                        dependsOnTaskId)) {

                                throw new InvalidDependencyException(
                                                "Dependency would create a cycle: "
                                                                + taskId
                                                                + " -> "
                                                                + dependsOnTaskId);
                        }

                        Task task = requireTask(taskId);
                        Task dependencyTask = requireTask(dependsOnTaskId);

                        Optional<TaskDependency> saved = taskDependencyRepository.save(
                                        new TaskDependency(
                                                        taskId,
                                                        dependsOnTaskId));

                        TaskDependency dependency = saved.orElseThrow(() -> new DuplicateDependencyException(
                                        "Task "
                                                        + taskId
                                                        + " already depends on "
                                                        + dependsOnTaskId));

                        boolean dependencyAlreadyDone = dependencyTask.getStatus() == TaskStatus.DONE;

                        adjustBlockingCount(
                                        task.getId(),
                                        dependencyAlreadyDone,
                                        1);

                        return dependency;
                }
        }

        public void removeDependency(
                        UUID taskId,
                        UUID dependsOnTaskId) {

                Objects.requireNonNull(
                                taskId,
                                "taskId must not be null");

                Objects.requireNonNull(
                                dependsOnTaskId,
                                "dependsOnTaskId must not be null");

                synchronized (dependencyGraphLock) {

                        requireTask(taskId);

                        Task dependencyTask = requireTask(dependsOnTaskId);

                        boolean removed = taskDependencyRepository.delete(
                                        taskId,
                                        dependsOnTaskId);

                        if (!removed) {
                                throw new DependencyNotFoundException(
                                                "No dependency from "
                                                                + taskId
                                                                + " to "
                                                                + dependsOnTaskId);
                        }

                        boolean dependencyCurrentlyDone = dependencyTask.getStatus() == TaskStatus.DONE;

                        adjustBlockingCount(
                                        taskId,
                                        dependencyCurrentlyDone,
                                        -1);
                }
        }

        private void adjustBlockingCount(
                        UUID taskId,
                        boolean dependsOnIsDone,
                        int delta) {

                int effectiveDelta = dependsOnIsDone ? 0 : delta;

                updateCounters(
                                taskId,
                                task -> withCounters(
                                                task,
                                                Math.max(
                                                                0,
                                                                task.getBlockingCount()
                                                                                + effectiveDelta),
                                                task.getOpenConstraintCount()));
        }

        private boolean wouldCreateCycle(
                        UUID taskId,
                        UUID dependsOnTaskId) {

                Set<UUID> visited = new HashSet<>();

                Deque<UUID> toVisit = new ArrayDeque<>();

                toVisit.push(dependsOnTaskId);

                while (!toVisit.isEmpty()) {

                        UUID current = toVisit.pop();

                        if (current.equals(taskId)) {
                                return true;
                        }

                        if (!visited.add(current)) {
                                continue;
                        }

                        for (TaskDependency dependency : taskDependencyRepository
                                        .findByTaskId(current)) {

                                toVisit.push(
                                                dependency
                                                                .getDependsOnTaskId());
                        }
                }

                return false;
        }

        public void onTaskStatusChanged(
                        UUID taskId,
                        TaskStatus newStatus) {

                Objects.requireNonNull(
                                taskId,
                                "taskId must not be null");

                Objects.requireNonNull(
                                newStatus,
                                "newStatus must not be null");

                synchronized (dependencyGraphLock) {

                        AtomicReference<Task> previous = new AtomicReference<>();

                        requireUpdated(
                                        taskRepository.update(
                                                        taskId,
                                                        task -> {
                                                                previous.set(task);

                                                                return copyOf(task)
                                                                                .status(newStatus)
                                                                                .build();
                                                        }),
                                        taskId);

                        TaskStatus previousStatus = previous.get().getStatus();

                        if (previousStatus != TaskStatus.DONE
                                        && newStatus == TaskStatus.DONE) {

                                adjustDependentsBlockingCount(
                                                taskId,
                                                -1);

                        } else if (previousStatus == TaskStatus.DONE
                                        && newStatus != TaskStatus.DONE) {

                                adjustDependentsBlockingCount(
                                                taskId,
                                                1);
                        }
                }
        }

        private void adjustDependentsBlockingCount(
                        UUID taskId,
                        int delta) {

                List<TaskDependency> dependents = taskDependencyRepository
                                .findByDependsOnTaskId(taskId);

                for (TaskDependency dependent : dependents) {

                        updateCounters(
                                        dependent.getTaskId(),
                                        task -> withCounters(
                                                        task,
                                                        Math.max(
                                                                        0,
                                                                        task.getBlockingCount()
                                                                                        + delta),
                                                        task.getOpenConstraintCount()));
                }
        }

        public void onConstraintAdded(UUID taskId) {

                Objects.requireNonNull(
                                taskId,
                                "taskId must not be null");

                updateCounters(
                                taskId,
                                task -> withCounters(
                                                task,
                                                task.getBlockingCount(),
                                                task.getOpenConstraintCount()
                                                                + 1));
        }

        public void onConstraintStatusChanged(
                        UUID taskId,
                        Constraint.ConstraintStatus previousStatus,
                        Constraint.ConstraintStatus newStatus) {

                Objects.requireNonNull(
                                taskId,
                                "taskId must not be null");

                Objects.requireNonNull(
                                previousStatus,
                                "previousStatus must not be null");

                Objects.requireNonNull(
                                newStatus,
                                "newStatus must not be null");

                boolean wasOpen = previousStatus == Constraint.ConstraintStatus.PENDING;

                boolean isOpenNow = newStatus == Constraint.ConstraintStatus.PENDING;

                if (wasOpen == isOpenNow) {
                        return;
                }

                int delta = isOpenNow ? 1 : -1;

                updateCounters(
                                taskId,
                                task -> withCounters(
                                                task,
                                                task.getBlockingCount(),
                                                Math.max(
                                                                0,
                                                                task.getOpenConstraintCount()
                                                                                + delta)));
        }

        public TaskEligibilityResponse getTaskEligibility(UUID taskId) {

                Objects.requireNonNull(
                                taskId,
                                "taskId must not be null");

                Task task = requireTask(taskId);

                return new TaskEligibilityResponse(
                                task.getId(),
                                task.isReady(),
                                task.getBlockingCount(),
                                task.getOpenConstraintCount());
        }
}