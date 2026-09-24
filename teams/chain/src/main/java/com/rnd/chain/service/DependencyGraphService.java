package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskAvailability;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DependencyGraphService {

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final ConstraintRepository constraintRepository;

    public DependencyGraphService(
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            ConstraintRepository constraintRepository
    ) {
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.constraintRepository = constraintRepository;
    }

    public TaskAvailability getBlockers(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");

        taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        List<TaskDependency> edges =
                taskDependencyRepository.findByTaskId(taskId);

        List<Task> blockingTasks;

        if (edges.isEmpty()) {
            blockingTasks = List.of();
        } else {
            List<UUID> blockerIds = edges.stream()
                    .map(TaskDependency::dependsOnTaskId)
                    .collect(Collectors.toList());

            blockingTasks =
                    taskRepository.findAllById(blockerIds);
        }

        List<Constraint> openConstraints =
                constraintRepository.findByTaskId(taskId)
                        .stream()
                        .filter(constraint ->
                                constraint.getStatus()
                                        == Constraint.ConstraintStatus.PENDING)
                        .collect(Collectors.toList());

        return new TaskAvailability(
                blockingTasks,
                openConstraints
        );
    }

    public List<Task> getAncestors(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        List<Task> ancestors = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();

        visited.add(taskId);

        UUID parentId = task.getParentTaskId();

        while (parentId != null && visited.add(parentId)) {

            Task parent =
                    taskRepository.findById(parentId)
                            .orElse(null);

            if (parent == null) {
                break;
            }

            ancestors.add(parent);
            parentId = parent.getParentTaskId();
        }

        return ancestors;
    }

    public List<Task> getDescendants(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");

        taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        List<Task> descendants = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();

        visited.add(taskId);

        Deque<UUID> toVisit = new ArrayDeque<>();
        toVisit.add(taskId);

        while (!toVisit.isEmpty()) {

            UUID currentId = toVisit.poll();

            for (Task child :
                    taskRepository.findByParentTaskId(currentId)) {

                if (visited.add(child.getId())) {
                    descendants.add(child);
                    toVisit.add(child.getId());
                }
            }
        }

        return descendants;
    }
}