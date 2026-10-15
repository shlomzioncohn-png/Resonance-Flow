package com.rnd.chain.repository;

import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.dto.TaskStateHistoryEntry;
import com.rnd.chain.dto.TaskStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("postgres")
@SpringBootTest
@Transactional
class PostgresTaskStateHistoryRepositoryTest {

    private final Instant baseTime = Instant.parse("2026-01-01T12:00:00Z");
    private final UUID taskId = UUID.randomUUID();

    @Autowired
    private PostgresTaskStateHistoryRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TaskStateHistoryEntry statusChange(Instant createdAt) {
        return TaskStateHistoryEntry.statusChanged(UUID.randomUUID(), taskId, TaskStatus.PENDING, TaskStatus.ACTIVE,
                createdAt);
    }

    @Test
    void record_roundTripsEveryEventType_withoutAnExistingTaskRow() {
        TaskStateHistoryEntry status = statusChange(baseTime);
        TaskStateHistoryEntry blocked = TaskStateHistoryEntry.blocked(UUID.randomUUID(), taskId, 2,
                baseTime.plusSeconds(1));
        TaskStateHistoryEntry unblocked = TaskStateHistoryEntry.unblocked(UUID.randomUUID(), taskId, 2,
                baseTime.plusSeconds(2));
        repository.record(status);
        repository.record(blocked);
        repository.record(unblocked);

        PageResponse<TaskStateHistoryEntry> page = repository.findByTaskId(taskId, null, null, 0, 10);

        assertThat(page.content()).containsExactly(unblocked, blocked, status);
        TaskStateHistoryEntry loaded = page.content().get(2);
        assertThat(loaded.fromStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(loaded.toStatus()).isEqualTo(TaskStatus.ACTIVE);
        assertThat(loaded.blockingCountBefore()).isNull();
        assertThat(page.content().get(1).blockingCountAfter()).isEqualTo(2);
    }

    @Test
    void findByTaskId_sameCreatedAt_ordersByInsertionOrderNewestFirst() {
        TaskStateHistoryEntry first = statusChange(baseTime);
        TaskStateHistoryEntry second = TaskStateHistoryEntry.blocked(UUID.randomUUID(), taskId, 1, baseTime);
        TaskStateHistoryEntry third = statusChange(baseTime);
        repository.record(first);
        repository.record(second);
        repository.record(third);

        assertThat(repository.findByTaskId(taskId, null, null, 0, 10).content())
                .containsExactly(third, second, first);
    }

    @Test
    void findByTaskId_ordersByCreatedAtBeforeInsertionOrder() {
        TaskStateHistoryEntry newest = statusChange(baseTime.plusSeconds(5));
        TaskStateHistoryEntry oldest = statusChange(baseTime);
        repository.record(newest);
        repository.record(oldest);

        assertThat(repository.findByTaskId(taskId, null, null, 0, 10).content())
                .containsExactly(newest, oldest);
    }

    @Test
    void findByTaskId_returnsOnlyThatTasksEntries() {
        TaskStateHistoryEntry mine = statusChange(baseTime);
        repository.record(mine);
        repository.record(TaskStateHistoryEntry.statusChanged(UUID.randomUUID(), UUID.randomUUID(),
                TaskStatus.ACTIVE, TaskStatus.DONE, baseTime));

        assertThat(repository.findByTaskId(taskId, null, null, 0, 10).content()).containsExactly(mine);
    }

    @Test
    void findByTaskId_rangeIsFromInclusiveToExclusive() {
        TaskStateHistoryEntry before = statusChange(baseTime);
        TaskStateHistoryEntry atFrom = statusChange(baseTime.plusSeconds(10));
        TaskStateHistoryEntry inside = statusChange(baseTime.plusSeconds(15));
        TaskStateHistoryEntry atTo = statusChange(baseTime.plusSeconds(20));
        repository.record(before);
        repository.record(atFrom);
        repository.record(inside);
        repository.record(atTo);

        assertThat(repository.findByTaskId(taskId, baseTime.plusSeconds(10), baseTime.plusSeconds(20), 0, 10)
                .content()).containsExactly(inside, atFrom);
    }

    @Test
    void findByTaskId_nullBoundsAreOpen() {
        TaskStateHistoryEntry early = statusChange(baseTime);
        TaskStateHistoryEntry late = statusChange(baseTime.plusSeconds(100));
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
            repository.record(statusChange(baseTime.plusSeconds(i)));
        }

        PageResponse<TaskStateHistoryEntry> page = repository.findByTaskId(taskId, null, null, 1, 2);

        assertThat(page.content()).hasSize(2);
        assertThat(page.content().get(0).createdAt()).isEqualTo(baseTime.plusSeconds(2));
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void database_rejectsRowWhoseShapeDoesNotMatchItsEventType() {
        // A BLOCKED row carrying a status bypasses the DTO's own validation, so only the CHECK stops it.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO task_state_history (id, task_id, event_type, from_status, blocking_count_before,
                                                blocking_count_after, created_at)
                VALUES (?, ?, 'BLOCKED', 'PENDING', 0, 1, ?)
                """, UUID.randomUUID(), taskId, Timestamp.from(baseTime)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
