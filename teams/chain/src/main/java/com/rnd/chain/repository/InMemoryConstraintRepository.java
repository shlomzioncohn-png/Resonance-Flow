package com.rnd.chain.repository;

import com.rnd.chain.dto.Constraint;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * In-memory fake of {@link ConstraintRepository}, for tests only - deliberately NOT a Spring bean
 * (no {@code @Repository}): production wiring uses {@code PostgresConstraintRepository} via
 * {@code ConstraintRepositoryConfig}, and two beans of this type would make the injection
 * ambiguous.
 */
public class InMemoryConstraintRepository implements ConstraintRepository {

    private final Map<String, Constraint> constraintsById;

    public InMemoryConstraintRepository() {
        this(List.of());
    }

    public InMemoryConstraintRepository(List<Constraint> constraints) {
        Objects.requireNonNull(constraints, "constraints must not be null");
        this.constraintsById = constraints.stream()
                .collect(Collectors.toMap(Constraint::getConstraintId, constraint -> constraint));
    }

    @Override
    public List<Constraint> findByTaskId(UUID taskId) {
        return constraintsById.values().stream()
                .filter(constraint -> constraint.getTaskId().equals(taskId))
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Constraint> findById(String constraintId) {
        return Optional.ofNullable(constraintsById.get(constraintId));
    }

    @Override
    public boolean save(Constraint constraint) {
        return constraintsById.put(constraint.getConstraintId(), constraint) == null;
    }

    @Override
    public Constraint.ConstraintStatus updateStatus(UUID taskId, String constraintId,
                                                     Constraint.ConstraintStatus newStatus,
                                                     Map<String, Object> reason, LocalDateTime updatedAt) {
        Constraint existing = constraintsById.get(constraintId);
        if (existing == null || !existing.getTaskId().equals(taskId)) {
            throw new IllegalStateException("No constraint " + constraintId + " on task " + taskId);
        }
        Constraint.ConstraintStatus previous = existing.getStatus();
        existing.setStatus(newStatus);
        existing.setReason(reason);
        existing.setUpdatedAt(updatedAt);
        return previous;
    }
}
