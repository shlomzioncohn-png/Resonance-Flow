package com.rnd.chain.service;

import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStateHistoryEntry;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.repository.InMemoryTaskStateHistoryRepository;
import com.rnd.chain.repository.InMemoryTransactionRunner;
import com.rnd.chain.testsupport.TaskFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskStateHistoryServiceTest {

    private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-02T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-03T10:00:00Z");

    private final UUID taskId = UUID.randomUUID();
    private InMemoryTaskStateHistoryRepository history;
    private TaskStateHistoryService service;

    @BeforeEach
    void setUp() {
        Task task = TaskFixtures.newTask(taskId);
        history = new InMemoryTaskStateHistoryRepository();
        service = new TaskStateHistoryService(
                new InMemoryTaskRepository(List.of(task)), history, new InMemoryTransactionRunner());
    }

    private void record(Instant at) {
        history.record(TaskStateHistoryEntry.statusChanged(
                UUID.randomUUID(), taskId, TaskStatus.PENDING, TaskStatus.DONE, at));
    }

    @Test
    void getHistory_unknownTask_throwsNotFound() {
        assertThatThrownBy(() -> service.getHistory(UUID.randomUUID(), null, null, 0, 20))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void getHistory_taskGoneButHistoryRemains_returnsTheHistory() {
        UUID deletedTaskId = UUID.randomUUID();
        history.record(TaskStateHistoryEntry.statusChanged(
                UUID.randomUUID(), deletedTaskId, TaskStatus.PENDING, TaskStatus.DONE, T1));

        PageResponse<TaskStateHistoryEntry> page = service.getHistory(deletedTaskId, null, null, 0, 20);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).taskId()).isEqualTo(deletedTaskId);
    }

    @Test
    void getHistory_taskGoneAndRangeMatchesNothing_stillReturnsEmptyPageNotNotFound() {
        UUID deletedTaskId = UUID.randomUUID();
        history.record(TaskStateHistoryEntry.statusChanged(
                UUID.randomUUID(), deletedTaskId, TaskStatus.PENDING, TaskStatus.DONE, T1));

        assertThat(service.getHistory(deletedTaskId, T2, T3, 0, 20).content()).isEmpty();
    }

    @Test
    void getHistory_existingTaskWithoutHistory_returnsEmptyPage() {
        PageResponse<TaskStateHistoryEntry> page = service.getHistory(taskId, null, null, 0, 20);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    void getHistory_fromIsInclusiveAndToIsExclusive() {
        record(T1);
        record(T2);
        record(T3);

        PageResponse<TaskStateHistoryEntry> page = service.getHistory(taskId, T1, T3, 0, 20);

        assertThat(page.content()).extracting(TaskStateHistoryEntry::createdAt).containsExactly(T2, T1);
    }

    @Test
    void getHistory_openBounds_returnsEverythingNewestFirst() {
        record(T1);
        record(T3);
        record(T2);

        assertThat(service.getHistory(taskId, null, null, 0, 20).content())
                .extracting(TaskStateHistoryEntry::createdAt).containsExactly(T3, T2, T1);
    }

    @Test
    void getHistory_fromEqualToTo_returnsEmptyPage() {
        record(T1);

        assertThat(service.getHistory(taskId, T1, T1, 0, 20).content()).isEmpty();
    }

    @Test
    void getHistory_fromAfterTo_throwsInvalidRequest() {
        assertThatThrownBy(() -> service.getHistory(taskId, T2, T1, 0, 20))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void getHistory_invalidPageOrSize_throwsInvalidRequest() {
        assertThatThrownBy(() -> service.getHistory(taskId, null, null, -1, 20))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.getHistory(taskId, null, null, 0, 0))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.getHistory(taskId, null, null, 0, 101))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void getHistory_pagesTheResult() {
        record(T1);
        record(T2);
        record(T3);

        PageResponse<TaskStateHistoryEntry> page = service.getHistory(taskId, null, null, 1, 2);

        assertThat(page.content()).extracting(TaskStateHistoryEntry::createdAt).containsExactly(T1);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
    }
}
