package com.rnd.chain.repository;

import com.rnd.chain.dto.UnresolvedDependency;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UnresolvedDependencyRepository {

    UnresolvedDependency save(UnresolvedDependency dependency);

    Optional<UnresolvedDependency> findByTaskIdAndDependsOnTaskId(
            UUID taskId,
            UUID dependsOnTaskId);

    List<UnresolvedDependency> findByDependsOnTaskId(
            UUID dependsOnTaskId);

    void deleteById(UUID id);
}