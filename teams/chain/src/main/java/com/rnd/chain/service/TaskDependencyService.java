package com.rnd.chain.service;

import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.exception.DependencyNotFoundException;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Enforces the business rules around the dependency graph on top of the
 * plain-CRUD {@link TaskDependencyRepository}: both tasks must exist, a
 * task cannot depend on itself, and a given edge cannot be added twice.
 */
@Service
public class TaskDependencyService {

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;

    public TaskDependencyService(TaskRepository taskRepository,
                                  TaskDependencyRepository taskDependencyRepository) {
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
    }

    /**
     * Creates a "taskId depends on dependsOnTaskId" edge.
     *
     * <p>The existence check and the edge creation are atomic (see
     * {@link TaskDependencyRepository#save}): concurrent callers racing to
     * add the same edge are guaranteed a deterministic outcome - exactly
     * one succeeds, the rest get {@link DuplicateDependencyException}.
     *
     * <p><b>Known assumption, not fixed here:</b> {@link #requireTaskExists}
     * runs before the atomic {@code save}, but task existence is checked
     * against a different repository ({@link TaskRepository}) than the one
     * the edge is saved to. If a task is concurrently soft-deleted between
     * that check and the save, this is a TOCTOU window this method does not
     * close.
     *
     * @throws InvalidDependencyException  if taskId equals dependsOnTaskId
     * @throws TaskNotFoundException       if either task does not exist
     * @throws DuplicateDependencyException if the edge already exists
     */
    public TaskDependency addDependency(UUID taskId, UUID dependsOnTaskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");

        if (taskId.equals(dependsOnTaskId)) {
            throw new InvalidDependencyException(
                    "Task " + taskId + " cannot depend on itself");
        }

        requireTaskExists(taskId);
        requireTaskExists(dependsOnTaskId);

        return taskDependencyRepository.save(new TaskDependency(taskId, dependsOnTaskId))
                .orElseThrow(() -> new DuplicateDependencyException(
                        "Task " + taskId + " already depends on " + dependsOnTaskId));
    }

    /**
     * Removes the "taskId depends on dependsOnTaskId" edge.
     *
     * @throws DependencyNotFoundException if no such edge exists
     */
    public void removeDependency(UUID taskId, UUID dependsOnTaskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");

        boolean removed = taskDependencyRepository.delete(taskId, dependsOnTaskId);
        if (!removed) {
            throw new DependencyNotFoundException(
                    "No dependency from " + taskId + " to " + dependsOnTaskId);
        }
    }

    /**
     * @return the ids of the tasks that the given task directly depends
     * on (what blocks it). Empty list if it has none.
     * @throws TaskNotFoundException if taskId does not exist
     */
    public List<UUID> getDependencies(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        requireTaskExists(taskId);

        return taskDependencyRepository.findByTaskId(taskId).stream()
                .map(TaskDependency::getDependsOnTaskId)
                .collect(Collectors.toList());
    }

    /**
     * @return the ids of the tasks that directly depend on the given
     * task (what it blocks). Empty list if nothing depends on it.
     * @throws TaskNotFoundException if taskId does not exist
     */
    public List<UUID> getDependents(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        requireTaskExists(taskId);

        return taskDependencyRepository.findByDependsOnTaskId(taskId).stream()
                .map(TaskDependency::getTaskId)
                .collect(Collectors.toList());
    }

    private void requireTaskExists(UUID taskId) {
        taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException("Task not found: " + taskId));
    }
}
