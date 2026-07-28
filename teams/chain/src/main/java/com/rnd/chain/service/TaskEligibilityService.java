package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// TODO: unverified — לוודא ששם המחלקה/package תואם למוסכמת הפרויקט
public class TaskEligibilityService {

    private final TaskRepository taskRepository; // TODO: unverified — לוודא שם/מיקום האמיתי
    private final TaskDependencyRepository taskDependencyRepository; // TODO: unverified — לוודא שם/מיקום האמיתי

    public TaskEligibilityService(TaskRepository taskRepository,
                                   TaskDependencyRepository taskDependencyRepository) {
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
    }

    @Transactional
    public void recalculateTaskEligibility(Inbox.TaskId taskId) {
        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        boolean newIsReady = task.getBlockingCount() == 0 && task.getOpenConstraintCount() == 0;

        if (task.isReady() != newIsReady) {
            task.setReady(newIsReady);
            taskRepository.save(task);
        }
    }

    @Transactional
    public void addDependency(Inbox.TaskId taskId, Inbox.TaskId dependsOnTaskId) {
        // TODO: unverified — לוודא מול TaskDependencyRepository האמיתי
        taskDependencyRepository.save(new TaskDependency(taskId, dependsOnTaskId));

        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        task.setBlockingCount(task.getBlockingCount() + 1);
        taskRepository.save(task);

        recalculateTaskEligibility(taskId);
    }

    @Transactional
    public void removeDependency(Inbox.TaskId taskId, Inbox.TaskId dependsOnTaskId) {
        // TODO: unverified — לוודא מול TaskDependencyRepository/ChainService האמיתיים
        taskDependencyRepository.delete(taskId, dependsOnTaskId);

        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        task.setBlockingCount(Math.max(0, task.getBlockingCount() - 1));
        taskRepository.save(task);

        recalculateTaskEligibility(taskId);
    }

    @Transactional
    public void onTaskStatusChanged(Inbox.TaskId taskId, TaskStatus newStatus) {
        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        task.setStatus(newStatus);
        taskRepository.save(task);

        if (newStatus == TaskStatus.DONE) {
            // TODO: unverified — צריך שאילתה "כל ה-TaskDependency שבהן dependsOnTaskId == taskId"
            List<TaskDependency> dependents = taskDependencyRepository.findByDependsOnTaskId(taskId);

            for (TaskDependency dep : dependents) {
                Inbox.TaskId dependentTaskId = dep.getTaskId();
                Task dependentTask = taskRepository.findById(dependentTaskId)
                    .orElseThrow(() -> new IllegalArgumentException("Task not found: " + dependentTaskId));
                dependentTask.setBlockingCount(Math.max(0, dependentTask.getBlockingCount() - 1));
                taskRepository.save(dependentTask);

                recalculateTaskEligibility(dependentTaskId);
            }
        }
    }
}