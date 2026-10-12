package com.rnd.chain.repository;

import com.rnd.chain.dto.UnresolvedDependency;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("!postgres")
public class InMemoryUnresolvedDependencyRepository
        implements UnresolvedDependencyRepository {

    private final Map<UUID, UnresolvedDependency> storage =
            new ConcurrentHashMap<>();

    @Override
    public UnresolvedDependency save(
            UnresolvedDependency dependency) {

        storage.put(
                dependency.id(),
                dependency);

        return dependency;
    }

    @Override
    public Optional<UnresolvedDependency>
            findByTaskIdAndDependsOnTaskId(
                    UUID taskId,
                    UUID dependsOnTaskId) {

        return storage.values()
                .stream()
                .filter(item ->
                        item.taskId().equals(taskId)
                                && item.dependsOnTaskId()
                                .equals(dependsOnTaskId))
                .findFirst();
    }

    @Override
    public List<UnresolvedDependency>
            findByDependsOnTaskId(
                    UUID dependsOnTaskId) {

        List<UnresolvedDependency> result =
                new ArrayList<>();

        for (UnresolvedDependency item
                : storage.values()) {

            if (item.dependsOnTaskId()
                    .equals(dependsOnTaskId)) {

                result.add(item);
            }
        }

        return result;
    }

    @Override
    public void deleteById(UUID id) {
        storage.remove(id);
    }
}