// Copied verbatim from Riki-Kaplan, unmodified.

package com.rnd.chain.repository;

import com.rnd.chain.dto.TaskDependency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link TaskDependencyRepository}, backed
 * by a synchronized list of edges. Seed data is passed in through the
 * constructor rather than hardcoded, since building real TaskDependency
 * instances requires a constructor owned by a teammate.
 */
public class InMemoryTaskDependencyRepository implements TaskDependencyRepository {

    private final List<TaskDependency> edges;

    public InMemoryTaskDependencyRepository(List<TaskDependency> edges) {
        Objects.requireNonNull(edges, "edges must not be null");
        this.edges = Collections.synchronizedList(new ArrayList<>(edges));
    }

    @Override
    public List<TaskDependency> findByTaskId(UUID taskId) {
        synchronized (edges) {
            return edges.stream()
                    .filter(edge -> edge.getTaskId().equals(taskId))
                    .collect(Collectors.toList());
        }
    }

    @Override
    public List<TaskDependency> findByDependsOnTaskId(UUID taskId) {
        synchronized (edges) {
            return edges.stream()
                    .filter(edge -> edge.getDependsOnTaskId().equals(taskId))
                    .collect(Collectors.toList());
        }
    }

    @Override
    public TaskDependency save(TaskDependency dependency) {
        Objects.requireNonNull(dependency, "dependency must not be null");
        synchronized (edges) {
            boolean alreadyExists = edges.stream().anyMatch(edge -> isSameEdge(edge, dependency));
            if (!alreadyExists) {
                edges.add(dependency);
            }
        }
        return dependency;
    }

    @Override
    public void delete(UUID taskId, UUID dependsOnTaskId) {
        synchronized (edges) {
            edges.removeIf(edge -> edge.getTaskId().equals(taskId)
                    && edge.getDependsOnTaskId().equals(dependsOnTaskId));
        }
    }

    private boolean isSameEdge(TaskDependency a, TaskDependency b) {
        return a.getTaskId().equals(b.getTaskId())
                && a.getDependsOnTaskId().equals(b.getDependsOnTaskId());
    }
}