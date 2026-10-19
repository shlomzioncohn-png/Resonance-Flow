package com.rnd.chain.repository;

import com.rnd.chain.dto.UnresolvedDependency;
import com.rnd.chain.repository.entity.UnresolvedDependencyEntity;
import com.rnd.chain.repository.jpa.SpringDataUnresolvedDependencyJpaRepository;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresUnresolvedDependencyRepository
        implements UnresolvedDependencyRepository {

    private final SpringDataUnresolvedDependencyJpaRepository repository;

    public PostgresUnresolvedDependencyRepository(
            SpringDataUnresolvedDependencyJpaRepository repository) {

        this.repository = repository;
    }

    @Override
    public UnresolvedDependency save(
            UnresolvedDependency dependency) {

        UnresolvedDependencyEntity entity =
                toEntity(dependency);

        return toDomain(
                repository.save(entity));
    }

    @Override
    public Optional<UnresolvedDependency>
            findByTaskIdAndDependsOnTaskId(
                    UUID taskId,
                    UUID dependsOnTaskId) {

        return repository
                .findByTaskIdAndDependsOnTaskId(
                        taskId,
                        dependsOnTaskId)
                .map(this::toDomain);
    }

    @Override
    public List<UnresolvedDependency>
            findByDependsOnTaskId(
                    UUID dependsOnTaskId) {

        return repository
                .findByDependsOnTaskId(
                        dependsOnTaskId)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void deleteById(UUID id) {
        repository.deleteById(id);
    }

    private UnresolvedDependencyEntity toEntity(
            UnresolvedDependency dependency) {

        return new UnresolvedDependencyEntity(
                dependency.id(),
                dependency.taskId(),
                dependency.dependsOnTaskId(),
                dependency.source(),
                dependency.sourceRef(),
                dependency.reason(),
                dependency.createdAt());
    }

    private UnresolvedDependency toDomain(
            UnresolvedDependencyEntity entity) {

        return new UnresolvedDependency(
                entity.getId(),
                entity.getTaskId(),
                entity.getDependsOnTaskId(),
                entity.getSource(),
                entity.getSourceRef(),
                entity.getReason(),
                entity.getCreatedAt());
    }
}