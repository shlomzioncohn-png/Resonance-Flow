package com.rnd.chain.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, protocol-agnostic audit record of one dependency-graph change.
 * sourceRef-required-for-MIND/SYSTEM is enforced in the service layer, not here.
 * Identity (equals/hashCode) is by {@code id} alone, not all fields - see below.
 */
public record DependencyHistoryEntry(UUID id,
                                      UUID taskId,
                                      UUID dependsOnTaskId,
                                      DependencyChangeAction action,
                                      DependencyChangeSource source,
                                      String sourceRef,
                                      Instant createdAt) {

    public DependencyHistoryEntry {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DependencyHistoryEntry other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link DependencyHistoryEntry}, useful given the number of constructor parameters. */
    public static class Builder {
        private UUID id;
        private UUID taskId;
        private UUID dependsOnTaskId;
        private DependencyChangeAction action;
        private DependencyChangeSource source;
        private String sourceRef;
        private Instant createdAt;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder taskId(UUID taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder dependsOnTaskId(UUID dependsOnTaskId) {
            this.dependsOnTaskId = dependsOnTaskId;
            return this;
        }

        public Builder action(DependencyChangeAction action) {
            this.action = action;
            return this;
        }

        public Builder source(DependencyChangeSource source) {
            this.source = source;
            return this;
        }

        public Builder sourceRef(String sourceRef) {
            this.sourceRef = sourceRef;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public DependencyHistoryEntry build() {
            return new DependencyHistoryEntry(id, taskId, dependsOnTaskId, action, source, sourceRef, createdAt);
        }
    }
}
