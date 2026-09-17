package com.rnd.chain.controller;

import com.rnd.chain.dto.DependencyChangeAction;
import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyHistoryResponseTest {

    @Test
    void from_copiesAllFieldsFromTheEntry() {
        DependencyHistoryEntry entry = DependencyHistoryEntry.builder()
                .id(UUID.randomUUID())
                .taskId(UUID.randomUUID())
                .dependsOnTaskId(UUID.randomUUID())
                .action(DependencyChangeAction.ADDED)
                .source(DependencyChangeSource.MIND)
                .sourceRef("evt-1")
                .createdAt(Instant.now())
                .build();

        DependencyHistoryResponse response = DependencyHistoryResponse.from(entry);

        assertThat(response.id()).isEqualTo(entry.id());
        assertThat(response.taskId()).isEqualTo(entry.taskId());
        assertThat(response.dependsOnTaskId()).isEqualTo(entry.dependsOnTaskId());
        assertThat(response.action()).isEqualTo(entry.action());
        assertThat(response.source()).isEqualTo(entry.source());
        assertThat(response.sourceRef()).isEqualTo(entry.sourceRef());
        assertThat(response.createdAt()).isEqualTo(entry.createdAt());
    }
}
