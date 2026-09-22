package com.rnd.chain.repository;

import com.rnd.chain.dto.Constraint;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link ConstraintRepository}. Fake
 * constraints are passed in through the constructor rather than
 * hardcoded, since building real Constraint instances requires a
 * constructor owned by a teammate.
 */

@Repository
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
    public Constraint save(Constraint constraint) {
        constraintsById.put(constraint.getConstraintId(), constraint);
        return constraint;
    }
}