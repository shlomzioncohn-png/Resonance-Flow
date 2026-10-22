package com.rnd.chain.service;

import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStateHistoryEntry;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.PostgresTaskRepository;
import com.rnd.chain.repository.PostgresTaskStateHistoryRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskStateHistoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.rnd.chain.testsupport.TaskFixtures.newTask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #774: the history row and the change it describes commit or roll back together. Not
 * {@code @Transactional} on the class (unlike the other Postgres service tests) - a wrapping
 * test transaction would swallow the very rollback this class needs to observe, so rows are
 * committed for real and removed in {@link #cleanUp()}.
 */
@Tag("postgres")
@SpringBootTest
class TaskStateHistoryRollbackPostgresTest {

    private static final LocalDateTime STORED_AT = LocalDateTime.of(2026, 1, 1, 12, 0);

    /** Delegates to the real Postgres repository, but fails on demand to simulate a history-write error. */
    @TestConfiguration
    static class FailingHistoryConfig {
        static final AtomicBoolean FAIL = new AtomicBoolean();

        @Bean
        @Primary
        TaskStateHistoryRepository failableTaskStateHistoryRepository(PostgresTaskStateHistoryRepository real) {
            return new TaskStateHistoryRepository() {
                @Override
                public TaskStateHistoryEntry record(TaskStateHistoryEntry entry) {
                    if (FAIL.get()) {
                        throw new IllegalStateException("simulated history write failure");
                    }
                    return real.record(entry);
                }

                @Override
                public PageResponse<TaskStateHistoryEntry> findByTaskId(UUID taskId, Instant from, Instant to,
                                                                       int page, int size) {
                    return real.findByTaskId(taskId, from, to, page, size);
                }
                    @Override
                public Map<UUID, Instant> findLatestBlockedSinceByTaskIds(
                    List<UUID> taskIds) {
                    return Map.of();
                }
            };
        }
    }

    @Autowired
    private PostgresTaskRepository taskRepository;

    @Autowired
    private TaskDependencyRepository dependencyRepository;

    @Autowired
    private PostgresTaskStateHistoryRepository historyRepository;

    @Autowired
    private TaskEligibilityService eligibilityService;

    @Autowired
    private DependencyEdgeService edgeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> createdTaskIds = new ArrayList<>();

    private UUID savedActiveTask() {
        UUID id = UUID.randomUUID();
        Task task = newTask(id).toBuilder().status(TaskStatus.ACTIVE).updatedAt(STORED_AT).build();
        taskRepository.save(task);
        createdTaskIds.add(id);
        return id;
    }

    private long historyRowCount(UUID taskId) {
        return historyRepository.findByTaskId(taskId, null, null, 0, 10).totalElements();
    }


    @AfterEach
    void cleanUp() {
        FailingHistoryConfig.FAIL.set(false);
        for (UUID id : createdTaskIds) {
            jdbcTemplate.update("DELETE FROM task_state_history WHERE task_id = ?", id);
            jdbcTemplate.update("DELETE FROM task_dependency WHERE task_id = ? OR depends_on_task_id = ?", id, id);
            jdbcTemplate.update("DELETE FROM dependency_history WHERE task_id = ? OR depends_on_task_id = ?", id, id);
            jdbcTemplate.update("DELETE FROM task WHERE id = ?", id);
        }
    }

    @Test
    void statusChange_commitsTheChangeAndExactlyOneHistoryRowTogether() {
        UUID id = savedActiveTask();

        eligibilityService.onTaskStatusChangedIfNewer(id, TaskStatus.PENDING, STORED_AT.plusSeconds(10),
                taskRepository.findById(id).orElseThrow().getTenantId(), null);

        assertThat(taskRepository.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(historyRowCount(id)).isEqualTo(1);
    }

    @Test
    void statusChange_historyWriteFailure_rollsBackTheStatusChangeToo() {
        UUID id = savedActiveTask();
        FailingHistoryConfig.FAIL.set(true);

        assertThatThrownBy(() -> eligibilityService.onTaskStatusChangedIfNewer(id, TaskStatus.PENDING,
                STORED_AT.plusSeconds(10), taskRepository.findById(id).orElseThrow().getTenantId(), null))
                .isInstanceOf(IllegalStateException.class);

        Task after = taskRepository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TaskStatus.ACTIVE);
        assertThat(after.getUpdatedAt()).isEqualTo(STORED_AT);
        assertThat(historyRowCount(id)).isZero();
    }

    @Test
    void blockingTransition_historyWriteFailure_rollsBackTheEdgeAndTheCounterToo() {
        UUID dependent = savedActiveTask();
        UUID blocker = savedActiveTask();
        FailingHistoryConfig.FAIL.set(true);

        assertThatThrownBy(() -> edgeService.addDependency(dependent, blocker))
                .isInstanceOf(IllegalStateException.class);

        assertThat(dependencyRepository.findByTaskId(dependent)).isEmpty();
        assertThat(taskRepository.findById(dependent).orElseThrow().getBlockingCount()).isZero();
        assertThat(historyRowCount(dependent)).isZero();
    }

    @Test
    void blockingTransition_commitsTheEdgeCounterAndOneBlockedRowTogether() {
        UUID dependent = savedActiveTask();
        UUID blocker = savedActiveTask();

        edgeService.addDependency(dependent, blocker);

        assertThat(dependencyRepository.findByTaskId(dependent)).hasSize(1);
        assertThat(taskRepository.findById(dependent).orElseThrow().getBlockingCount()).isEqualTo(1);
        assertThat(historyRowCount(dependent)).isEqualTo(1);
    }
}
