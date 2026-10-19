// Originally copied verbatim from chain/ruthHoltzman/dependency-repository-66; confidence added
// for issue #445 (priority_score) - flag any further change here with Ruth, who owns this type.

package com.rnd.chain.dto;

import java.util.Objects;
import java.util.UUID;

// Identifier type: UUID, pending Inbox publishing its own task id type. Values are Task.id
// references (see Task.java) and are not validated or looked up here.
//
// taskId depends on (is blocked by) dependsOnTaskId. This class only models the edge; it does
// not validate that either id refers to an existing Task, and it intentionally does not reject
// self-dependencies (taskId == dependsOnTaskId) - that check belongs to the service layer,
// which has access to the repository needed to enforce it against existing edges.
//
// confidence (issue #445): Mind's confidence (0..1) in this specific edge, from
// task.dependencies.proposed. Used by PriorityScoreService to weight how much an unresolved
// blocker (or a dependent) moves priority_score - a 0.3-confidence blocker should move it less
// than a 0.9-confidence one. Edges not sourced from Mind (added via the USER-facing API) have no
// Mind confidence, so they default to full confidence (1.0) via the two-arg constructor below -
// this keeps every existing call site that only knows (taskId, dependsOnTaskId) compiling
// unchanged.
public record TaskDependency(UUID taskId, UUID dependsOnTaskId, Double confidence) {

    public TaskDependency {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
        if (confidence != null && (confidence < 0.0 || confidence > 1.0)) {
            throw new IllegalArgumentException("confidence must be between 0 and 1, got " + confidence);
        }
    }

    /** Edge with no Mind-supplied confidence - defaults to full confidence (1.0). */
    public TaskDependency(UUID taskId, UUID dependsOnTaskId) {
        this(taskId, dependsOnTaskId, 1.0);
    }
}
