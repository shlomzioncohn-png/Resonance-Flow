
package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
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
    private final TaskEligibilityService taskEligibilityService;

    public ConstraintService(
            ConstraintRepository constraintRepository,
            TaskRepository taskRepository,
            TaskEligibilityService taskEligibilityService) {

        this.constraintRepository = constraintRepository;
        this.taskRepository = taskRepository;
        this.taskEligibilityService = taskEligibilityService;
    }

    public Constraint createConstraint(
            UUID taskId,
            Constraint.ConstraintType type,
            Map<String, Object> reason,
            String sourceRef) {

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

        taskEligibilityService.onConstraintAdded(taskId);

        return constraint;
    }

    public Constraint updateConstraintStatus(
            UUID taskId,
            String constraintId,
            Constraint.ConstraintStatus newStatus) {

        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(constraintId, "constraintId must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");

        Constraint constraint = constraintRepository.findById(constraintId)
                .filter(c -> c.getTaskId().equals(taskId))
                .orElseThrow(() ->
                        new ConstraintNotFoundException(constraintId));

        Constraint.ConstraintStatus previousStatus = constraint.getStatus();

        constraint.setStatus(newStatus);
        constraint.setUpdatedAt(LocalDateTime.now());

        constraintRepository.save(constraint);

        taskEligibilityService.onConstraintStatusChanged(
                taskId,
                previousStatus,
                newStatus
        );

        return constraint;
    }

    public List<Constraint> getTaskConstraints(UUID taskId) {

        Objects.requireNonNull(taskId, "taskId must not be null");

        taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        return constraintRepository.findByTaskId(taskId);
    }
}