package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// TODO: unverified — לוודא ששם המחלקה/package תואם למוסכמת הפרויקט
public class TaskEligibilityService {

    private static final Logger log = LoggerFactory.getLogger(TaskEligibilityService.class);

    private final TaskRepository taskRepository; // TODO: unverified — לוודא שם/מיקום האמיתי
    private final TaskDependencyRepository taskDependencyRepository; // TODO: unverified — לוודא שם/מיקום האמיתי

    public TaskEligibilityService(TaskRepository taskRepository,
                                   TaskDependencyRepository taskDependencyRepository) {
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
    }

    @Transactional
    public void recalculateTaskEligibility(UUID taskId) {
        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        boolean newIsReady = task.getBlockingCount() == 0 && task.getOpenConstraintCount() == 0;

        if (task.isReady() != newIsReady) {
            log.info("Task {} eligibility changed: isReady {} -> {}", taskId, task.isReady(), newIsReady);
            task.setReady(newIsReady);
            taskRepository.save(task);
        } else {
            log.debug("Task {} eligibility unchanged (isReady={})", taskId, newIsReady);
        }
    }

    @Transactional
    public void addDependency(UUID taskId, UUID dependsOnTaskId) {
        log.info("Adding dependency: task {} depends on {}", taskId, dependsOnTaskId);

        // TODO: unverified — לוודא מול TaskDependencyRepository האמיתי
        taskDependencyRepository.save(new TaskDependency(taskId, dependsOnTaskId));

        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        task.setBlockingCount(task.getBlockingCount() + 1);
        taskRepository.save(task);

        recalculateTaskEligibility(taskId);
    }

    @Transactional
    public void removeDependency(UUID taskId, UUID dependsOnTaskId) {
        log.info("Removing dependency: task {} no longer depends on {}", taskId, dependsOnTaskId);

        // TODO: unverified — לוודא מול TaskDependencyRepository/ChainService האמיתיים
        taskDependencyRepository.delete(taskId, dependsOnTaskId);

        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        task.setBlockingCount(Math.max(0, task.getBlockingCount() - 1));
        taskRepository.save(task);

        recalculateTaskEligibility(taskId);
    }

    @Transactional
    public void onTaskStatusChanged(UUID taskId, TaskStatus newStatus) {
        log.info("Task {} status changed to {}", taskId, newStatus);

        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        task.setStatus(newStatus);
        taskRepository.save(task);

        if (newStatus == TaskStatus.DONE) {
            // TODO: unverified — צריך שאילתה "כל ה-TaskDependency שבהן dependsOnTaskId == taskId"
            List<TaskDependency> dependents = taskDependencyRepository.findByDependsOnTaskId(taskId);

            log.info("Task {} completed, recalculating {} dependent task(s)", taskId, dependents.size());

            for (TaskDependency dep : dependents) {
                UUID dependentTaskId = dep.getTaskId();
                Task dependentTask = taskRepository.findById(dependentTaskId)
                    .orElseThrow(() -> new IllegalArgumentException("Task not found: " + dependentTaskId));
                dependentTask.setBlockingCount(Math.max(0, dependentTask.getBlockingCount() - 1));
                taskRepository.save(dependentTask);

                recalculateTaskEligibility(dependentTaskId);
            }
        }
    }
}