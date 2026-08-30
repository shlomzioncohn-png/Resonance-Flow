package com.rnd.chain.repository;

import com.rnd.chain.dto.Task;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link TaskRepository}, backed by a
 * {@link ConcurrentHashMap} keyed by task id. Seed data is passed in
 * through the constructor rather than hardcoded, since building real
 * Task instances requires Task's constructor, owned by a teammate.
 */
public class InMemoryTaskRepository implements TaskRepository {

    private final Map<UUID, Task> tasksById;

    public InMemoryTaskRepository(List<Task> tasks) {
        Objects.requireNonNull(tasks, "tasks must not be null");
        this.tasksById = new ConcurrentHashMap<>();
        tasks.forEach(task -> tasksById.put(task.getId(), task));
    }

    @Override
    public Optional<Task> findById(UUID id) {
        return Optional.ofNullable(tasksById.get(id))
                .filter(this::isNotDeleted);
    }

    @Override
    public List<Task> findAllById(List<UUID> ids) {
        return ids.stream()
                .map(tasksById::get)
                .filter(Objects::nonNull)
                .filter(this::isNotDeleted)
                .collect(Collectors.toList());
    }

    @Override
    public List<Task> findByParentTaskId(UUID parentTaskId) {
        return tasksById.values().stream()
                .filter(task -> Objects.equals(task.getParentTaskId(), parentTaskId))
                .filter(this::isNotDeleted)
                .collect(Collectors.toList());
    }

    @Override
    public Task save(Task task) {
        tasksById.put(task.getId(), task);
        return task;
    }

    /**
     * Soft-deleted tasks (deletedAt != null) are treated as not found.
     */
    private boolean isNotDeleted(Task task) {
        return task.getDeletedAt() == null;
    }
}