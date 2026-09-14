package com.rnd.chain.controller;

import com.rnd.chain.dto.DependencyChangeAction;
import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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
                .createdAt(LocalDateTime.now())
                .build();

        DependencyHistoryResponse response = DependencyHistoryResponse.from(entry);

        assertThat(response.id()).isEqualTo(entry.getId());
        assertThat(response.taskId()).isEqualTo(entry.getTaskId());
        assertThat(response.dependsOnTaskId()).isEqualTo(entry.getDependsOnTaskId());
        assertThat(response.action()).isEqualTo(entry.getAction());
        assertThat(response.source()).isEqualTo(entry.getSource());
        assertThat(response.sourceRef()).isEqualTo(entry.getSourceRef());
        assertThat(response.createdAt()).isEqualTo(entry.getCreatedAt());
    }
}
