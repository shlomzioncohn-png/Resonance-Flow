package com.rnd.chain.dto;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable audit record of a single dependency-graph change (an edge
 * added or removed). Deliberately protocol-agnostic - no dependency on
 * REST/Kafka/persistence types, so the same representation applies
 * regardless of how it was recorded or how it will later be published.
 *
 * <p>This DTO only validates that its fields are structurally present; the
 * business rule that {@code sourceRef} is required for {@code MIND}/
 * {@code SYSTEM} sources is enforced by the service layer, not here.
 */
public final class DependencyHistoryEntry {

    private final UUID id;
    private final UUID taskId;
    private final UUID dependsOnTaskId;
    private final DependencyChangeAction action;
    private final DependencyChangeSource source;
    private final String sourceRef;
    private final LocalDateTime createdAt;

    private DependencyHistoryEntry(UUID id,
                                    UUID taskId,
                                    UUID dependsOnTaskId,
                                    DependencyChangeAction action,
                                    DependencyChangeSource source,
                                    String sourceRef,
                                    LocalDateTime createdAt) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (taskId == null) {
            throw new IllegalArgumentException("taskId must not be null");
        }
        if (dependsOnTaskId == null) {
            throw new IllegalArgumentException("dependsOnTaskId must not be null");
        }
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
        this.id = id;
        this.taskId = taskId;
        this.dependsOnTaskId = dependsOnTaskId;
        this.action = action;
        this.source = source;
        this.sourceRef = sourceRef;
        this.createdAt = createdAt;
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

    public LocalDateTime getCreatedAt() {
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
        private LocalDateTime createdAt;

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

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public DependencyHistoryEntry build() {
            return new DependencyHistoryEntry(id, taskId, dependsOnTaskId, action, source, sourceRef, createdAt);
        }
    }
}
