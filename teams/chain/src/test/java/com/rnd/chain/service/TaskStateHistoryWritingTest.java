package com.rnd.chain.service;

import com.rnd.chain.testsupport.PgRepos;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStateEventType;
import com.rnd.chain.dto.TaskStateHistoryEntry;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTestFixtures;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.repository.GraphLock;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.repository.TaskStateHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #774: task_state_history is written exactly once per applied change, never for a rejected one. */
/** Ported from the in-memory version; runs against the real Postgres repositories (rolled back per test). */
@Tag("postgres")
@SpringBootTest
@Transactional
class TaskStateHistoryWritingTest {

    private static final UUID TENANT = TaskTestFixtures.DEFAULT_TENANT_ID;

    private TaskRepository taskRepository;
    private TaskStateHistoryRepository history;
    private TaskEligibilityService service;
    private DependencyEdgeService edgeService;

    @Autowired
    private TaskReadinessRepository taskReadinessRepository;

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
                        new SchedulingUpdateService(event -> { },taskReadinessRepository),
                        new SchedulingPublishListener(
                        taskRepository,
                        new SchedulingUpdateService(event -> { },taskReadinessRepository)));

        TaskStateHistoryRecorder recorder =
                new TaskStateHistoryRecorder(history, Clock.systemUTC());

        edgeService = new DependencyEdgeService(
                taskRepository,
                dependencyRepository,
                lock,
                priorityScoreService,
                recorder,
                taskReadinessRepository);

        service = new TaskEligibilityService(
                taskRepository,
                dependencyRepository,
                lock,
                edgeService,
                priorityScoreService,
                recorder,
                taskReadinessRepository);

    }

    private UUID savedTask(TaskStatus status, LocalDateTime updatedAt) {
        UUID id = UUID.randomUUID();
        taskRepository.save(TaskTestFixtures.defaultTask(id).status(status).updatedAt(updatedAt).build());
        return id;
    }

    private UUID savedTask() {
        return savedTask(TaskStatus.ACTIVE, LocalDateTime.now());
    }

    /** Oldest first, so assertions read in the order things happened. */
    private List<TaskStateHistoryEntry> rowsOf(UUID taskId) {
        List<TaskStateHistoryEntry> rows = new ArrayList<>(
                history.findByTaskId(taskId, null, null, 0, 1000).content());
        java.util.Collections.reverse(rows);
        return rows;
    }

    @Test
    void statusChangedIfNewer_appliedEvent_writesExactlyOneRow() {
        LocalDateTime stored = LocalDateTime.now();
        UUID id = savedTask(TaskStatus.ACTIVE, stored);

        service.onTaskStatusChangedIfNewer(id, TaskStatus.PENDING, stored.plusSeconds(10), TENANT, null);

        List<TaskStateHistoryEntry> rows = rowsOf(id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).eventType()).isEqualTo(TaskStateEventType.STATUS_CHANGED);
        assertThat(rows.get(0).fromStatus()).isEqualTo(TaskStatus.ACTIVE);
        assertThat(rows.get(0).toStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void statusChangedIfNewer_staleEvent_writesNoRow() {
        LocalDateTime stored = LocalDateTime.now();
        UUID id = savedTask(TaskStatus.ACTIVE, stored);

        service.onTaskStatusChangedIfNewer(id, TaskStatus.PENDING, stored.minusSeconds(10), TENANT, null);

        assertThat(rowsOf(id)).isEmpty();
        assertThat(taskRepository.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.ACTIVE);
    }

    @Test
    void statusChangedIfNewer_redeliveredEvent_writesOnlyOneRow() {
        LocalDateTime stored = LocalDateTime.now();
        UUID id = savedTask(TaskStatus.ACTIVE, stored);
        LocalDateTime eventTime = stored.plusSeconds(10);

        service.onTaskStatusChangedIfNewer(id, TaskStatus.PENDING, eventTime, TENANT, null);
        service.onTaskStatusChangedIfNewer(id, TaskStatus.PENDING, eventTime, TENANT, null);

        assertThat(rowsOf(id)).hasSize(1);
    }

    @Test
    void statusChangedIfNewer_newerEventWithSameStatus_writesNoRow() {
        LocalDateTime stored = LocalDateTime.now();
        UUID id = savedTask(TaskStatus.ACTIVE, stored);

        service.onTaskStatusChangedIfNewer(id, TaskStatus.ACTIVE, stored.plusSeconds(10), TENANT, null);

        assertThat(rowsOf(id)).isEmpty();
    }

    @Test
    void statusChanged_writesRowOnlyWhenStatusActuallyChanges() {
        UUID id = savedTask(TaskStatus.ACTIVE, LocalDateTime.now());

        service.onTaskStatusChanged(id, TaskStatus.ACTIVE);
        assertThat(rowsOf(id)).isEmpty();

        service.onTaskStatusChanged(id, TaskStatus.DONE);
        assertThat(rowsOf(id)).hasSize(1);
        assertThat(rowsOf(id).get(0).toStatus()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void taskCompleted_writesOneRow_andARepeatWritesNone() {
        LocalDateTime stored = LocalDateTime.now();
        UUID id = savedTask(TaskStatus.ACTIVE, stored);

        service.onTaskCompleted(id, stored.plusSeconds(10));
        service.onTaskCompleted(id, stored.plusSeconds(20));

        List<TaskStateHistoryEntry> rows = rowsOf(id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).fromStatus()).isEqualTo(TaskStatus.ACTIVE);
        assertThat(rows.get(0).toStatus()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void taskCompleted_staleTimestamp_writesNoRow() {
        LocalDateTime stored = LocalDateTime.now();
        UUID id = savedTask(TaskStatus.ACTIVE, stored);

        service.onTaskCompleted(id, stored.minusSeconds(10));

        assertThat(rowsOf(id)).isEmpty();
    }

    @Test
    void blockingCount_recordsOnlyZeroCrossings() {
        UUID dependent = savedTask();
        UUID blockerA = savedTask();
        UUID blockerB = savedTask();

        edgeService.addDependency(dependent, blockerA);
        edgeService.addDependency(dependent, blockerB);
        service.onTaskStatusChanged(blockerA, TaskStatus.DONE);
        service.onTaskStatusChanged(blockerB, TaskStatus.DONE);
        service.onTaskStatusChanged(blockerA, TaskStatus.ACTIVE);

        List<TaskStateHistoryEntry> rows = rowsOf(dependent);
        assertThat(rows).extracting(TaskStateHistoryEntry::eventType)
                .containsExactly(
                        TaskStateEventType.BLOCKED,
                        TaskStateEventType.UNBLOCKED,
                        TaskStateEventType.BLOCKED);
        assertThat(rows.get(0).blockingCountAfter()).isEqualTo(1);
        assertThat(rows.get(1).blockingCountBefore()).isEqualTo(1);
    }

    @Test
    void removingTheLastBlockingEdge_recordsUnblocked() {
        UUID dependent = savedTask();
        UUID blocker = savedTask();
        edgeService.addDependency(dependent, blocker);

        edgeService.removeDependency(dependent, blocker);

        assertThat(rowsOf(dependent)).extracting(TaskStateHistoryEntry::eventType)
                .containsExactly(TaskStateEventType.BLOCKED, TaskStateEventType.UNBLOCKED);
    }

    @Test
    void completedBlocker_writesItsOwnStatusRow_andTheDependentsUnblockedRow() {
        UUID dependent = savedTask();
        UUID blocker = savedTask();
        edgeService.addDependency(dependent, blocker);

        service.onTaskStatusChanged(blocker, TaskStatus.DONE);

        assertThat(rowsOf(blocker)).extracting(TaskStateHistoryEntry::eventType)
                .containsExactly(TaskStateEventType.STATUS_CHANGED);
        assertThat(rowsOf(dependent)).extracting(TaskStateHistoryEntry::eventType)
                .containsExactly(TaskStateEventType.BLOCKED, TaskStateEventType.UNBLOCKED);
    }

    @Test
    void historyWrite_doesNotChangeAnyOtherTaskField() {
        LocalDateTime stored = LocalDateTime.now();
        UUID id = UUID.randomUUID();
        Task original = TaskTestFixtures.defaultTask(id)
                .status(TaskStatus.ACTIVE)
                .updatedAt(stored)
                .score(7.0)
                .confidence(0.5)
                .priorityScore(4.0)
                .build();
        taskRepository.save(original);
        LocalDateTime eventTime = stored.plusSeconds(10);

        service.onTaskStatusChangedIfNewer(id, TaskStatus.PENDING, eventTime, TENANT, null);

        Task after = taskRepository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(after.getUpdatedAt()).isEqualTo(eventTime);
        assertThat(after.getPriorityScore()).isEqualTo(original.getPriorityScore());
        assertThat(after.getScore()).isEqualTo(original.getScore());
    }
}
