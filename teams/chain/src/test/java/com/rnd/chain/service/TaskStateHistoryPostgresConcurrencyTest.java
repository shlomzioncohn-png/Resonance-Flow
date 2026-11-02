package com.rnd.chain.service;

import com.rnd.chain.dto.TaskStateEventType;
import com.rnd.chain.dto.TaskStateHistoryEntry;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTestFixtures;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.repository.TaskStateHistoryRepository;
import com.rnd.chain.testsupport.ConcurrentTestRunner;
import com.rnd.chain.testsupport.PgCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.rnd.chain.events.SchedulingUpdatePublisher;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #774 under real concurrency: task_state_history is written exactly once per applied change. Not
 * {@code @Transactional}, so every thread commits through its own connection; rows created here are removed in
 * {@code @AfterEach} (run against a disposable database).
 */
@Tag("postgres")
@SpringBootTest
class TaskStateHistoryPostgresConcurrencyTest {

    // Not about Kafka: a mock keeps the afterCommit scheduling publish from blocking on a broker.
    @MockBean
    private SchedulingUpdatePublisher schedulingUpdatePublisher;

    private static final UUID TENANT = TaskTestFixtures.DEFAULT_TENANT_ID;

    @Autowired
    private TaskEligibilityService service;

    @Autowired
    private DependencyEdgeService edgeService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskStateHistoryRepository history;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private PgCleanup cleanup;

    @BeforeEach
    void setUp() {
        cleanup = new PgCleanup(jdbcTemplate);
    }

    @AfterEach
    void cleanUpRows() {
        cleanup.restore();
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
        List<TaskStateHistoryEntry> rows = new ArrayList<>(history.findByTaskId(taskId, null, null, 0, 1000).content());
        java.util.Collections.reverse(rows);
        return rows;
    }

    @Test
    void concurrentRedeliveryOfTheSameEvent_writesExactlyOneRow() throws InterruptedException {
        LocalDateTime stored = LocalDateTime.now();
        UUID id = savedTask(TaskStatus.ACTIVE, stored);
        LocalDateTime eventTime = stored.plusSeconds(10);

        ConcurrentTestRunner.run(16, () ->
                service.onTaskStatusChangedIfNewer(id, TaskStatus.DONE, eventTime, TENANT, null));

        assertThat(rowsOf(id)).hasSize(1);
    }

    @Test
    void concurrentEdges_onOneDependent_writeExactlyOneBlockedRow() throws InterruptedException {
        UUID hub = savedTask();
        List<UUID> blockers = IntStream.range(0, 16).mapToObj(i -> savedTask()).collect(Collectors.toList());

        ConcurrentTestRunner.run(16, i -> edgeService.addDependency(hub, blockers.get(i)));

        assertThat(rowsOf(hub)).extracting(TaskStateHistoryEntry::eventType)
                .containsExactly(TaskStateEventType.BLOCKED);
    }

    @Test
    void concurrentCompletionOfAllBlockers_writesExactlyOneUnblockedRow() throws InterruptedException {
        UUID hub = savedTask();
        List<UUID> blockers = IntStream.range(0, 16).mapToObj(i -> savedTask()).collect(Collectors.toList());
        blockers.forEach(b -> edgeService.addDependency(hub, b));

        ConcurrentTestRunner.run(16, i -> service.onTaskStatusChanged(blockers.get(i), TaskStatus.DONE));

        assertThat(rowsOf(hub)).extracting(TaskStateHistoryEntry::eventType)
                .containsExactly(TaskStateEventType.BLOCKED, TaskStateEventType.UNBLOCKED);
        blockers.forEach(b -> assertThat(rowsOf(b)).hasSize(1));
    }
}
