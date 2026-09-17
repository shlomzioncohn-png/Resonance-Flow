package com.rnd.chain.service;

import com.rnd.chain.dto.DependencyHistoryEntry;
import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.repository.DependencyHistoryRepository;
import com.rnd.chain.repository.PostgresTaskDependencyRepository;
import com.rnd.chain.repository.PostgresTaskRepository;
import com.rnd.chain.repository.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static com.rnd.chain.testsupport.TaskFixtures.newTask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Proves the real wiring (Postgres repositories + {@link com.rnd.chain.repository.JpaTransactionRunner})
 * behaves the same as the in-memory pair {@link TaskDependencyServiceTest} already covers -
 * PERSISTENCE_PLAN.md §8 checkpoint 5. */
@SpringBootTest
@Transactional
class TaskDependencyServicePostgresIntegrationTest {

    @Autowired
    private PostgresTaskRepository taskRepository;

    @Autowired
    private PostgresTaskDependencyRepository taskDependencyRepository;

    @Autowired
    private DependencyHistoryRepository historyRepository;

    @Autowired
    private TransactionRunner transactionRunner;

    @Autowired
    private Clock clock;

    @Test
    void addDependencyThenRemoveDependency_recordHistoryInTheSameTransaction() {
        TaskDependencyService service = new TaskDependencyService(
                taskRepository, taskDependencyRepository, historyRepository, transactionRunner, clock);
        Task a = taskRepository.save(newTask(UUID.randomUUID()));
        Task b = taskRepository.save(newTask(UUID.randomUUID()));

        service.addDependency(a.getId(), b.getId());
        assertThat(taskDependencyRepository.findByTaskId(a.getId()))
                .containsExactly(new TaskDependency(a.getId(), b.getId()));
        PageResponse<DependencyHistoryEntry> afterAdd = service.getHistory(a.getId(), 0, 10);
        assertThat(afterAdd.content()).hasSize(1);

        service.removeDependency(a.getId(), b.getId());
        assertThat(taskDependencyRepository.findByTaskId(a.getId())).isEmpty();
        assertThat(service.getHistory(a.getId(), 0, 10).content()).hasSize(2);
    }

    @Test
    void addDependency_compensatingActionRemovesEdge_whenHistoryWriteFails() {
        DependencyHistoryRepository throwingHistoryRepository = mock(DependencyHistoryRepository.class);
        when(throwingHistoryRepository.findBySourceAndSourceRef(any(), any())).thenReturn(Optional.empty());
        when(throwingHistoryRepository.record(any())).thenThrow(new RuntimeException("simulated history write failure"));

        TaskDependencyService service = new TaskDependencyService(
                taskRepository, taskDependencyRepository, throwingHistoryRepository, transactionRunner, clock);
        Task a = taskRepository.save(newTask(UUID.randomUUID()));
        Task b = taskRepository.save(newTask(UUID.randomUUID()));

        assertThatThrownBy(() -> service.addDependency(a.getId(), b.getId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated history write failure");

        // recordHistoryOrCompensate's manual compensating delete still runs against the real
        // Postgres-backed repository - no dangling edge, same as the in-memory pair.
        assertThat(taskDependencyRepository.findByTaskId(a.getId())).isEmpty();
    }
}
