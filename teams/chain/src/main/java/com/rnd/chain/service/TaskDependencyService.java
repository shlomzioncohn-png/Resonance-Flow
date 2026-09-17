package com.rnd.chain.service;

import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import org.springframework.stereotype.Service;
import java.util.stream.Collectors;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class TaskDependencyService {

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final TaskEligibilityService taskEligibilityService;

    public TaskDependencyService(
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            TaskEligibilityService taskEligibilityService) {

        this.taskRepository =
                Objects.requireNonNull(
                        taskRepository,
                        "taskRepository must not be null");

        this.taskDependencyRepository =
                Objects.requireNonNull(
                        taskDependencyRepository,
                        "taskDependencyRepository must not be null");

        this.taskEligibilityService =
                Objects.requireNonNull(
                        taskEligibilityService,
                        "taskEligibilityService must not be null");
    }

    public TaskDependency addDependency(
            UUID taskId,
            UUID dependsOnTaskId) {

        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(
                dependsOnTaskId,
                "dependsOnTaskId must not be null");

        if (taskId.equals(dependsOnTaskId)) {
            throw new InvalidDependencyException(
                    "Task " + taskId + " cannot depend on itself");
        }

        requireTaskExists(taskId);
        requireTaskExists(dependsOnTaskId);

        return taskEligibilityService.addDependency(
                taskId,
                dependsOnTaskId);
    }

    public void removeDependency(
            UUID taskId,
            UUID dependsOnTaskId) {

        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(
                dependsOnTaskId,
                "dependsOnTaskId must not be null");

        requireTaskExists(taskId);
        requireTaskExists(dependsOnTaskId);

        taskEligibilityService.removeDependency(
                taskId,
                dependsOnTaskId);
    }

    public List<UUID> getDependencies(UUID taskId) {

        Objects.requireNonNull(taskId, "taskId must not be null");
        requireTaskExists(taskId);

        return taskDependencyRepository
                .findByTaskId(taskId)
                .stream()
                .map(TaskDependency::getDependsOnTaskId)
                .collect(Collectors.toList());
    }

    public List<UUID> getDependents(UUID taskId) {

        Objects.requireNonNull(taskId, "taskId must not be null");
        requireTaskExists(taskId);

        return taskDependencyRepository
                .findByDependsOnTaskId(taskId)
                .stream()
                .map(TaskDependency::getTaskId)
                .collect(Collectors.toList());
    }

    private void requireTaskExists(UUID taskId) {

        taskRepository.findById(taskId)
                .orElseThrow(() ->
                        new TaskNotFoundException(taskId));
    }
}