package com.rnd.chain.repository;

import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.dto.TaskStateHistoryEntry;
import com.rnd.chain.dto.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTaskStateHistoryRepositoryTest {

    private final InMemoryTaskStateHistoryRepository repository = new InMemoryTaskStateHistoryRepository();
    private final Instant baseTime = Instant.parse("2026-01-01T12:00:00Z");
    private final UUID taskId = UUID.randomUUID();

    private TaskStateHistoryEntry statusChange(UUID task, Instant createdAt) {
        return TaskStateHistoryEntry.statusChanged(UUID.randomUUID(), task, TaskStatus.PENDING, TaskStatus.ACTIVE,
                createdAt);
    }

    @Test
    void record_returnsEntryAndMakesItRetrievable() {
        TaskStateHistoryEntry entry = statusChange(taskId, baseTime);

        assertThat(repository.record(entry)).isSameAs(entry);

        PageResponse<TaskStateHistoryEntry> page = repository.findByTaskId(taskId, null, null, 0, 10);
        assertThat(page.content()).containsExactly(entry);
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void record_doesNotDedup_sameContentTwice() {
        repository.record(statusChange(taskId, baseTime));
        repository.record(statusChange(taskId, baseTime));

        assertThat(repository.findByTaskId(taskId, null, null, 0, 10).totalElements()).isEqualTo(2);
    }

    @Test
    void findByTaskId_returnsOnlyThatTasksEntries() {
        TaskStateHistoryEntry mine = statusChange(taskId, baseTime);
        repository.record(mine);
        repository.record(statusChange(UUID.randomUUID(), baseTime));

        assertThat(repository.findByTaskId(taskId, null, null, 0, 10).content()).containsExactly(mine);
    }

    @Test
    void findByTaskId_ordersNewestFirst() {
        TaskStateHistoryEntry oldest = statusChange(taskId, baseTime);
        TaskStateHistoryEntry middle = statusChange(taskId, baseTime.plusSeconds(1));
        TaskStateHistoryEntry newest = statusChange(taskId, baseTime.plusSeconds(2));
        repository.record(middle);
        repository.record(newest);
        repository.record(oldest);

        assertThat(repository.findByTaskId(taskId, null, null, 0, 10).content())
                .containsExactly(newest, middle, oldest);
    }

    @Test
    void findByTaskId_sameCreatedAt_ordersByInsertionOrderNewestFirst() {
        TaskStateHistoryEntry first = statusChange(taskId, baseTime);
        TaskStateHistoryEntry second = TaskStateHistoryEntry.blocked(UUID.randomUUID(), taskId, 1, baseTime);
        TaskStateHistoryEntry third = statusChange(taskId, baseTime);
        repository.record(first);
        repository.record(second);
        repository.record(third);

        assertThat(repository.findByTaskId(taskId, null, null, 0, 10).content())
                .containsExactly(third, second, first);
    }

    @Test
    void findByTaskId_rangeIsFromInclusiveToExclusive() {
        TaskStateHistoryEntry before = statusChange(taskId, baseTime);
        TaskStateHistoryEntry atFrom = statusChange(taskId, baseTime.plusSeconds(10));
        TaskStateHistoryEntry inside = statusChange(taskId, baseTime.plusSeconds(15));
        TaskStateHistoryEntry atTo = statusChange(taskId, baseTime.plusSeconds(20));
        repository.record(before);
        repository.record(atFrom);
        repository.record(inside);
        repository.record(atTo);

        assertThat(repository.findByTaskId(taskId, baseTime.plusSeconds(10), baseTime.plusSeconds(20), 0, 10)
                .content()).containsExactly(inside, atFrom);
    }

    @Test
    void findByTaskId_nullBoundsAreOpen() {
        TaskStateHistoryEntry early = statusChange(taskId, baseTime);
        TaskStateHistoryEntry late = statusChange(taskId, baseTime.plusSeconds(100));
        repository.record(early);
        repository.record(late);

        assertThat(repository.findByTaskId(taskId, baseTime.plusSeconds(50), null, 0, 10).content())
                .containsExactly(late);
        assertThat(repository.findByTaskId(taskId, null, baseTime.plusSeconds(50), 0, 10).content())
                .containsExactly(early);
    }

    @Test
    void findByTaskId_paginates() {
        for (int i = 0; i < 5; i++) {
            repository.record(statusChange(taskId, baseTime.plusSeconds(i)));
        }

        PageResponse<TaskStateHistoryEntry> page = repository.findByTaskId(taskId, null, null, 1, 2);

        assertThat(page.content()).hasSize(2);
        assertThat(page.content().get(0).createdAt()).isEqualTo(baseTime.plusSeconds(2));
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void findByTaskId_pageBeyondEnd_returnsEmptyContent() {
        repository.record(statusChange(taskId, baseTime));

        assertThat(repository.findByTaskId(taskId, null, null, Integer.MAX_VALUE, 10).content()).isEmpty();
    }
}
