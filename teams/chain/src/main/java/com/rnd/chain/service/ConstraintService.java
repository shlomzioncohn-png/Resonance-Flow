package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.exception.ConstraintNotFoundException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.TaskRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ConstraintService {

    private final ConstraintRepository constraintRepository;
    private final TaskRepository taskRepository;

    public ConstraintService(
            ConstraintRepository constraintRepository,
            TaskRepository taskRepository
    ) {
        this.constraintRepository = constraintRepository;
        this.taskRepository = taskRepository;
    }

    public Constraint createConstraint(
            UUID taskId,
            Constraint.ConstraintType type,
            Map<String, Object> reason,
            String sourceRef
    ) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(type, "type must not be null");

        taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        LocalDateTime now = LocalDateTime.now();

        Constraint constraint = new Constraint(
                UUID.randomUUID().toString(),
                taskId,
                type,
                Constraint.ConstraintStatus.PENDING,
                reason,
                sourceRef,
                now,
                now
        );

        constraintRepository.save(constraint);
        recalculateTaskEligibility(taskId);

        return constraint;
    }

    public Constraint updateConstraintStatus(
            String constraintId,
            Constraint.ConstraintStatus newStatus
    ) {
        Objects.requireNonNull(constraintId, "constraintId must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");

        Constraint constraint = constraintRepository.findById(constraintId)
                .orElseThrow(() -> new ConstraintNotFoundException(constraintId));

        constraint.setStatus(newStatus);
        constraint.setUpdatedAt(LocalDateTime.now());

        constraintRepository.save(constraint);

        recalculateTaskEligibility(constraint.getTaskId());

        return constraint;
    }

    public List<Constraint> getTaskConstraints(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");

        taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        return constraintRepository.findByTaskId(taskId);
    }

    public void recalculateTaskEligibility(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        List<Constraint> constraints =
                constraintRepository.findByTaskId(taskId);

        long openCount = constraints.stream()
                .filter(constraint ->
                        constraint.getStatus()
                                == Constraint.ConstraintStatus.PENDING)
                .count();

        boolean ready =
                task.getBlockingCount() == 0
                        && openCount == 0;

        task.setOpenConstraintCount((int) openCount);
        task.setReady(ready);

        taskRepository.save(task);
    }
}