package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskEligibilityResponse;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class TaskEligibilityService {

    private static final Logger log = LoggerFactory.getLogger(TaskEligibilityService.class);

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final SchedulingUpdateService schedulingUpdateService;

    public TaskEligibilityService(
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository, SchedulingUpdateService schedulingUpdateService) {
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.schedulingUpdateService = schedulingUpdateService;

    }

    public void recalculateTaskEligibility(UUID taskId) {

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Task not found: " + taskId));

        boolean newIsReady = task.getBlockingCount() == 0
                && task.getOpenConstraintCount() == 0;

        if (task.isReady() != newIsReady) {

            log.info(
                    "Task {} eligibility changed: isReady {} -> {}",
                    taskId,
                    task.isReady(),
                    newIsReady);

            task.setReady(newIsReady);
            schedulingUpdateService.publishSchedulingUpdate(task);
            taskRepository.save(task);

        } else {

            log.debug(
                    "Task {} eligibility unchanged (isReady={})",
                    taskId,
                    newIsReady);
        }
    }

    public void addDependency(
            UUID taskId,
            UUID dependsOnTaskId) {
        log.info(
                "Adding dependency: task {} depends on {}",
                taskId,
                dependsOnTaskId);

        taskDependencyRepository.save(
                new TaskDependency(
                        taskId,
                        dependsOnTaskId));

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Task not found: " + taskId));

        task.setBlockingCount(
                task.getBlockingCount() + 1);

        taskRepository.save(task);

        recalculateTaskEligibility(taskId);
    }

    public void removeDependency(
            UUID taskId,
            UUID dependsOnTaskId) {
        log.info(
                "Removing dependency: task {} no longer depends on {}",
                taskId,
                dependsOnTaskId);

        taskDependencyRepository.delete(
                taskId,
                dependsOnTaskId);

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Task not found: " + taskId));

        task.setBlockingCount(
                Math.max(
                        0,
                        task.getBlockingCount() - 1));

        taskRepository.save(task);

        recalculateTaskEligibility(taskId);
    }

    public void onTaskStatusChanged(
            UUID taskId,
            TaskStatus newStatus) {
        log.info(
                "Task {} status changed to {}",
                taskId,
                newStatus);

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Task not found: " + taskId));

        task.setStatus(newStatus);
        taskRepository.save(task);

        if (newStatus == TaskStatus.DONE) {

            List<TaskDependency> dependents = taskDependencyRepository
                    .findByDependsOnTaskId(taskId);

            log.info(
                    "Task {} completed, recalculating {} dependent task(s)",
                    taskId,
                    dependents.size());

            for (TaskDependency dependency : dependents) {

                UUID dependentTaskId = dependency.getTaskId();

                Task dependentTask = taskRepository
                        .findById(dependentTaskId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Task not found: "
                                        + dependentTaskId));

                dependentTask.setBlockingCount(
                        Math.max(
                                0,
                                dependentTask.getBlockingCount() - 1));

                taskRepository.save(dependentTask);

                recalculateTaskEligibility(
                        dependentTaskId);
            }
        }
    }

    public TaskEligibilityResponse getTaskEligibility(UUID taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Task not found: " + taskId));

        return new TaskEligibilityResponse(
                task.getId(),
                task.isReady(),
                task.getBlockingCount(),
                task.getOpenConstraintCount());
    }
}