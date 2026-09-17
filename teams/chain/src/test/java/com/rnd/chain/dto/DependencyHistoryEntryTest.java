package com.rnd.chain.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DependencyHistoryEntryTest {

    @Test
    void builder_throwsNullPointerException_whenIdIsNull() {
        assertThatThrownBy(() -> newEntryBuilder().id(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("id");
    }

    @Test
    void builder_throwsNullPointerException_whenTaskIdIsNull() {
        assertThatThrownBy(() -> newEntryBuilder().taskId(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("taskId");
    }

    @Test
    void builder_throwsNullPointerException_whenDependsOnTaskIdIsNull() {
        assertThatThrownBy(() -> newEntryBuilder().dependsOnTaskId(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("dependsOnTaskId");
    }

    @Test
    void builder_throwsNullPointerException_whenActionIsNull() {
        assertThatThrownBy(() -> newEntryBuilder().action(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("action");
    }

    @Test
    void builder_throwsNullPointerException_whenSourceIsNull() {
        assertThatThrownBy(() -> newEntryBuilder().source(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source");
    }

    @Test
    void builder_throwsNullPointerException_whenCreatedAtIsNull() {
        assertThatThrownBy(() -> newEntryBuilder().createdAt(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("createdAt");
    }

    @Test
    void builder_allowsNullSourceRef() {
        DependencyHistoryEntry entry = newEntryBuilder().sourceRef(null).build();

        assertThat(entry.sourceRef()).isNull();
    }

    @Test
    void equals_isReflexive() {
        DependencyHistoryEntry entry = newEntryBuilder().build();

        assertThat(entry).isEqualTo(entry);
    }

    @Test
    void equals_isFalse_whenComparedToNull() {
        DependencyHistoryEntry entry = newEntryBuilder().build();

        assertThat(entry.equals(null)).isFalse();
    }

    @Test
    void equals_isFalse_whenComparedToDifferentType() {
        DependencyHistoryEntry entry = newEntryBuilder().build();

        assertThat(entry.equals("not an entry")).isFalse();
    }

    @Test
    void equals_andHashCode_dependOnlyOnId() {
        UUID id = UUID.randomUUID();
        DependencyHistoryEntry a = newEntryBuilder().id(id).action(DependencyChangeAction.ADDED).build();
        DependencyHistoryEntry b = newEntryBuilder().id(id).action(DependencyChangeAction.REMOVED).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void equals_isFalse_whenIdsDiffer_evenIfOtherFieldsMatch() {
        DependencyHistoryEntry a = newEntryBuilder().id(UUID.randomUUID()).build();
        DependencyHistoryEntry b = newEntryBuilder().id(UUID.randomUUID()).build();

        assertThat(a).isNotEqualTo(b);
    }

    private static DependencyHistoryEntry.Builder newEntryBuilder() {
        return DependencyHistoryEntry.builder()
                .id(UUID.randomUUID())
                .taskId(UUID.randomUUID())
                .dependsOnTaskId(UUID.randomUUID())
                .action(DependencyChangeAction.ADDED)
                .source(DependencyChangeSource.USER)
                .sourceRef(null)
                .createdAt(Instant.now());
    }
}
