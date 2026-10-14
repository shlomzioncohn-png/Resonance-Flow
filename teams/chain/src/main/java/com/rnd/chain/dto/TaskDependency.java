// Copied verbatim from chain/ruthHoltzman/dependency-repository-66, unmodified.

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
public record TaskDependency(UUID taskId, UUID dependsOnTaskId) {

    public TaskDependency {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
    }
}
