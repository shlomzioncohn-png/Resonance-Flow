package com.rnd.chain.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, protocol-agnostic audit record of one dependency-graph change.
 * sourceRef-required-for-MIND/SYSTEM is enforced in the service layer, not here.
 */
public final class DependencyHistoryEntry {

    private final UUID id;
    private final UUID taskId;
    private final UUID dependsOnTaskId;
    private final DependencyChangeAction action;
    private final DependencyChangeSource source;
    private final String sourceRef;
    private final Instant createdAt;

    private DependencyHistoryEntry(UUID id,
                                    UUID taskId,
                                    UUID dependsOnTaskId,
                                    DependencyChangeAction action,
                                    DependencyChangeSource source,
                                    String sourceRef,
                                    Instant createdAt) {
        requireNonNull(id, "id");
        requireNonNull(taskId, "taskId");
        requireNonNull(dependsOnTaskId, "dependsOnTaskId");
        requireNonNull(action, "action");
        requireNonNull(source, "source");
        requireNonNull(createdAt, "createdAt");
        this.id = id;
        this.taskId = taskId;
        this.dependsOnTaskId = dependsOnTaskId;
        this.action = action;
        this.source = source;
        this.sourceRef = sourceRef;
        this.createdAt = createdAt;
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public UUID getDependsOnTaskId() {
        return dependsOnTaskId;
    }

    public DependencyChangeAction getAction() {
        return action;
    }

    public DependencyChangeSource getSource() {
        return source;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DependencyHistoryEntry)) {
            return false;
        }
        return id.equals(((DependencyHistoryEntry) o).id);
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
