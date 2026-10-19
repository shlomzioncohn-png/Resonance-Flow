package com.rnd.chain.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.chain.dto.TaskStateEventType;
import com.rnd.chain.dto.TaskStateHistoryEntry;
import com.rnd.chain.dto.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStateHistoryResponseTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void from_statusChanged_copiesStatusesAndLeavesCountsNull() {
        TaskStateHistoryEntry entry = TaskStateHistoryEntry.statusChanged(
                UUID.randomUUID(), UUID.randomUUID(), TaskStatus.PENDING, TaskStatus.DONE, NOW);

        TaskStateHistoryResponse response = TaskStateHistoryResponse.from(entry);

        assertThat(response.id()).isEqualTo(entry.id());
        assertThat(response.taskId()).isEqualTo(entry.taskId());
        assertThat(response.eventType()).isEqualTo(TaskStateEventType.STATUS_CHANGED);
        assertThat(response.fromStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(response.toStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(response.blockingCountBefore()).isNull();
        assertThat(response.blockingCountAfter()).isNull();
        assertThat(response.createdAt()).isEqualTo(NOW);
    }

    @Test
    void from_blocked_copiesCountsAndLeavesStatusesNull() {
        TaskStateHistoryEntry entry = TaskStateHistoryEntry.blocked(
                UUID.randomUUID(), UUID.randomUUID(), 2, NOW);

        TaskStateHistoryResponse response = TaskStateHistoryResponse.from(entry);

        assertThat(response.eventType()).isEqualTo(TaskStateEventType.BLOCKED);
        assertThat(response.blockingCountBefore()).isZero();
        assertThat(response.blockingCountAfter()).isEqualTo(2);
        assertThat(response.fromStatus()).isNull();
        assertThat(response.toStatus()).isNull();
    }

    @Test
    void from_unblocked_copiesCountsAndLeavesStatusesNull() {
        TaskStateHistoryEntry entry = TaskStateHistoryEntry.unblocked(
                UUID.randomUUID(), UUID.randomUUID(), 3, NOW);

        TaskStateHistoryResponse response = TaskStateHistoryResponse.from(entry);

        assertThat(response.eventType()).isEqualTo(TaskStateEventType.UNBLOCKED);
        assertThat(response.blockingCountBefore()).isEqualTo(3);
        assertThat(response.blockingCountAfter()).isZero();
        assertThat(response.fromStatus()).isNull();
    }

    @Test
    void json_omitsFieldsThatDoNotApplyToTheEventType() {
        ObjectNode json = mapper.valueToTree(TaskStateHistoryResponse.from(
                TaskStateHistoryEntry.blocked(UUID.randomUUID(), UUID.randomUUID(), 1, NOW)));

        assertThat(json.has("fromStatus")).isFalse();
        assertThat(json.has("toStatus")).isFalse();
        assertThat(json.get("blockingCountBefore").asInt()).isZero();
        assertThat(json.get("blockingCountAfter").asInt()).isEqualTo(1);
    }

    @Test
    void json_keepsZeroCounts() {
        ObjectNode json = mapper.valueToTree(TaskStateHistoryResponse.from(
                TaskStateHistoryEntry.unblocked(UUID.randomUUID(), UUID.randomUUID(), 1, NOW)));

        assertThat(json.has("blockingCountAfter")).isTrue();
        assertThat(json.get("blockingCountAfter").asInt()).isZero();
    }
}
