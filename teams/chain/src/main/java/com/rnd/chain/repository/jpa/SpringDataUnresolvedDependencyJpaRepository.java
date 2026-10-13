package com.rnd.chain.repository.jpa;

import com.rnd.chain.repository.entity.UnresolvedDependencyEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataUnresolvedDependencyJpaRepository
        extends JpaRepository<UnresolvedDependencyEntity, UUID> {

    Optional<UnresolvedDependencyEntity>
            findByTaskIdAndDependsOnTaskId(
                    UUID taskId,
                    UUID dependsOnTaskId);

    List<UnresolvedDependencyEntity>
            findByDependsOnTaskId(
                    UUID dependsOnTaskId);
}