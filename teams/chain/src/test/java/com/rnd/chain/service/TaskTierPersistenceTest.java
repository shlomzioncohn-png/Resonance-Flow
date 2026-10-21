package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTestFixtures;
import com.rnd.chain.dto.TaskTier;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.repository.GraphLock;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.repository.TaskStateHistoryRepository;
import com.rnd.chain.testsupport.PgRepos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #864: task.updated stores the latest tier under the same newest-wins check as the status. */
@Tag("postgres")
@SpringBootTest
@Transactional
class TaskTierPersistenceTest {

    private static final UUID TENANT = TaskTestFixtures.DEFAULT_TENANT_ID;

    @Autowired
    private TaskReadinessRepository taskReadinessRepository;

    private TaskRepository taskRepository;
    private TaskStateHistoryRepository history;
    private TaskEligibilityService service;

    @BeforeEach
    void setUp() {
        taskRepository = PgRepos.tasks(List.of());
        history = PgRepos.stateHistory();
        TaskDependencyRepository dependencyRepository = PgRepos.dependencies(List.of());
        GraphLock lock = PgRepos.graphLock();
        PriorityScoreService priorityScoreService =
                new PriorityScoreService(
                        taskRepository,
                        dependencyRepository,
                        taskReadinessRepository,
                        new SchedulingUpdateService(event -> { }),
                        new SchedulingPublishListener(taskRepository, new SchedulingUpdateService(event -> { })));

        TaskCounterUpdater counterUpdater = new TaskCounterUpdater(
                taskRepository,
                priorityScoreService,
                taskReadinessRepository);
        TaskStateHistoryRecorder recorder = new TaskStateHistoryRecorder(history, Clock.systemUTC());
        service = new TaskEligibilityService(taskRepository, dependencyRepository, lock, counterUpdater, priorityScoreService, recorder,
                taskReadinessRepository);
    }

    private UUID savedTask(TaskTier tier, LocalDateTime updatedAt) {
        UUID id = UUID.randomUUID();
        taskRepository.save(TaskTestFixtures.defaultTask(id)
                .status(TaskStatus.ACTIVE).tier(tier).updatedAt(updatedAt).build());
        return id;
    }

    private TaskTier storedTier(UUID id) {
        return taskRepository.findById(id).orElseThrow().getTier();
    }

    @Test
    void newTaskFromUpdatedEvent_storesTier() {
        UUID id = UUID.randomUUID();

        service.onTaskStatusChangedIfNewer(id, TaskStatus.ACTIVE, LocalDateTime.now(), TENANT, TaskTier.TACTICAL);

        assertThat(storedTier(id)).isEqualTo(TaskTier.TACTICAL);
    }

    @Test
    void existingTask_newerEventWithTier_updatesTier() {
        LocalDateTime stored = LocalDateTime.now();
        UUID id = savedTask(TaskTier.OPERATIONAL, stored);

        service.onTaskStatusChangedIfNewer(id, TaskStatus.ACTIVE, stored.plusSeconds(10), TENANT, TaskTier.STRATEGIC);

        assertThat(storedTier(id)).isEqualTo(TaskTier.STRATEGIC);
    }

    @Test
    void existingTask_newerEventWithoutTier_keepsStoredTier() {
        LocalDateTime stored = LocalDateTime.now();
        UUID id = savedTask(TaskTier.TACTICAL, stored);

        service.onTaskStatusChangedIfNewer(id, TaskStatus.PENDING, stored.plusSeconds(10), TENANT, null);

        assertThat(storedTier(id)).isEqualTo(TaskTier.TACTICAL);
    }

    @Test
    void staleEvent_doesNotChangeTier() {
        LocalDateTime stored = LocalDateTime.now();
        UUID id = savedTask(TaskTier.TACTICAL, stored);

        service.onTaskStatusChangedIfNewer(id, TaskStatus.ACTIVE, stored.minusSeconds(10), TENANT, TaskTier.STRATEGIC);

        assertThat(storedTier(id)).isEqualTo(TaskTier.TACTICAL);
    }

    @Test
    void tierOnlyChange_writesNoHistoryRow() {
        LocalDateTime stored = LocalDateTime.now();
        UUID id = savedTask(TaskTier.OPERATIONAL, stored);

        service.onTaskStatusChangedIfNewer(id, TaskStatus.ACTIVE, stored.plusSeconds(10), TENANT, TaskTier.STRATEGIC);

        assertThat(history.findByTaskId(id, null, null, 0, 1000).content()).isEmpty();
    }

    @Test
    void tierOnlyChange_leavesPriorityScoreAndReadinessUnchanged() {
        LocalDateTime stored = LocalDateTime.now();
        UUID id = UUID.randomUUID();
        taskRepository.save(TaskTestFixtures.defaultTask(id)
                .status(TaskStatus.ACTIVE).isReady(true).priorityScore(7.5).updatedAt(stored).build());

        service.onTaskStatusChangedIfNewer(id, TaskStatus.ACTIVE, stored.plusSeconds(10), TENANT, TaskTier.STRATEGIC);

        Task after = taskRepository.findById(id).orElseThrow();
        assertThat(after.getTier()).isEqualTo(TaskTier.STRATEGIC);
        assertThat(after.getPriorityScore()).isEqualTo(7.5);
        assertThat(after.isReady()).isTrue();
    }
}
