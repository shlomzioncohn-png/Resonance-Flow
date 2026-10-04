package com.rnd.chain.dto;

import java.util.List;
import java.util.UUID;

/**
 * Full cross-tier context for a single task: everything reachable on the
 * dependency graph axis, and everything reachable on the hierarchy axis,
 * kept in two clearly separate sections so the two axes are never merged
 * into one undifferentiated list.
 *
 * JSON shape:
 * {
 *   "taskId": "...",
 *   "dependencyGraph": {
 *     "directDependencies": [ TaskResponse, ... ],
 *     "allDependencies":    [ TaskResponse, ... ],
 *     "directDependents":   [ TaskResponse, ... ],
 *     "allDependents":      [ TaskResponse, ... ]
 *   },
 *   "hierarchy": {
 *     "ancestors":      [ TaskResponse, ... ],  // nearest-first: index 0 is the direct parent
 *     "directChildren": [ TaskResponse, ... ],
 *     "allDescendants": [ TaskResponse, ... ]
 *   }
 * }
 */
public class TaskTraceResponse {

    private final UUID taskId;
    private final DependencyGraphSection dependencyGraph;
    private final HierarchySection hierarchy;

    public TaskTraceResponse(UUID taskId, DependencyGraphSection dependencyGraph, HierarchySection hierarchy) {
        this.taskId = taskId;
        this.dependencyGraph = dependencyGraph;
        this.hierarchy = hierarchy;
    }

    public UUID getTaskId() { return taskId; }
    public DependencyGraphSection getDependencyGraph() { return dependencyGraph; }
    public HierarchySection getHierarchy() { return hierarchy; }

    /**
     * The same-tier dependency axis: direct and full/transitive dependencies,
     * direct and full/transitive dependents.
     */
    public static class DependencyGraphSection {
        private final List<TaskResponse> directDependencies;
        private final List<TaskResponse> allDependencies;
        private final List<TaskResponse> directDependents;
        private final List<TaskResponse> allDependents;

        public DependencyGraphSection(List<TaskResponse> directDependencies,
                                       List<TaskResponse> allDependencies,
                                       List<TaskResponse> directDependents,
                                       List<TaskResponse> allDependents) {
            this.directDependencies = directDependencies;
            this.allDependencies = allDependencies;
            this.directDependents = directDependents;
            this.allDependents = allDependents;
        }

        public List<TaskResponse> getDirectDependencies() { return directDependencies; }
        public List<TaskResponse> getAllDependencies() { return allDependencies; }
        public List<TaskResponse> getDirectDependents() { return directDependents; }
        public List<TaskResponse> getAllDependents() { return allDependents; }
    }

    /**
     * The parent/child hierarchy axis: the ancestor chain (nearest parent
     * first), direct children, and all descendants.
     */
    public static class HierarchySection {
        private final List<TaskResponse> ancestors;
        private final List<TaskResponse> directChildren;
        private final List<TaskResponse> allDescendants;

        public HierarchySection(List<TaskResponse> ancestors,
                                 List<TaskResponse> directChildren,
                                 List<TaskResponse> allDescendants) {
            this.ancestors = ancestors;
            this.directChildren = directChildren;
            this.allDescendants = allDescendants;
        }

        public List<TaskResponse> getAncestors() { return ancestors; }
        public List<TaskResponse> getDirectChildren() { return directChildren; }
        public List<TaskResponse> getAllDescendants() { return allDescendants; }
    }
}
