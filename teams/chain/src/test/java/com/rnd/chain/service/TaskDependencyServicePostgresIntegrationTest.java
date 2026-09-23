package com.rnd.chain.service;

import com.rnd.chain.dto.DependencyHistoryEntry;
import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.repository.DependencyHistoryRepository;
import com.rnd.chain.repository.PostgresTaskDependencyRepository;
import com.rnd.chain.repository.PostgresTaskRepository;
import com.rnd.chain.repository.TransactionRunner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

import static com.rnd.chain.testsupport.TaskFixtures.newTask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves the real wiring (Postgres repositories + {@link com.rnd.chain.repository.JpaTransactionRunner})
 * behaves the same as the in-memory pair {@link TaskDependencyServiceTest} already covers. */
@Tag("postgres")
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
    void addDependency_compensatesSpeculativeHistoryEntry_whenEdgeWriteIsANoOp() {
        // Current ordering (post round-2 review on #373): history is recorded speculatively
        // BEFORE the edge write, since the edge - once written - is what TaskEligibilityService
        // reads outside this lock and must be final. If the edge write turns out to be a no-op
        // (duplicate), it's the speculative history entry that gets compensated (deleted), not
        // the edge - proven here against the real Postgres-backed repositories, no mocks needed.
        TaskDependencyService service = new TaskDependencyService(
                taskRepository, taskDependencyRepository, historyRepository, transactionRunner, clock);
        Task a = taskRepository.save(newTask(UUID.randomUUID()));
        Task b = taskRepository.save(newTask(UUID.randomUUID()));
        service.addDependency(a.getId(), b.getId());
        assertThat(service.getHistory(a.getId(), 0, 10).content()).hasSize(1);

        assertThatThrownBy(() -> service.addDependency(a.getId(), b.getId()))
                .isInstanceOf(DuplicateDependencyException.class);

        // The edge from the first call is untouched, and no phantom second ADDED entry survives.
        assertThat(taskDependencyRepository.findByTaskId(a.getId()))
                .containsExactly(new TaskDependency(a.getId(), b.getId()));
        assertThat(service.getHistory(a.getId(), 0, 10).content()).hasSize(1);
    }
}
