package com.rnd.chain.service;

import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.testsupport.TaskStateHistoryFixtures;
import com.rnd.chain.testsupport.PgRepos;

import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTestFixtures;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.events.SchedulingUpdatePublisher;
import com.rnd.chain.events.TaskSchedulingUpdatedEvent;
import com.rnd.chain.exception.CrossTenantDependencyException;
import com.rnd.chain.exception.DependencyNotFoundException;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.GraphLock;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.testsupport.TestTransactions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("postgres")
@SpringBootTest
@Transactional
class DependencyEdgeServiceTest {

    private TaskRepository taskRepository;
    private TaskDependencyRepository dependencyRepository;

    private RecordingSchedulingUpdatePublisher publisher;

    private DependencyEdgeService service;

    @Autowired
    private TaskReadinessRepository taskReadinessRepository;

    private TaskReadinessSnapshot readiness(Task task) {
        return taskReadinessRepository.findByIds(List.of(task.getId())).get(task.getId());
    }

    private int blockingCount(Task task) {
        return readiness(task).blockingCount();
    }

    private int openConstraintCount(Task task) {
        return readiness(task).openConstraintCount();
    }

    private boolean isReady(Task task) {
        return readiness(task).isReady();
    }

    @BeforeEach
    void setUp() {

        taskRepository =
                PgRepos.tasks(List.of());

        dependencyRepository =
                PgRepos.dependencies(List.of());

        publisher =
                new RecordingSchedulingUpdatePublisher();

        SchedulingUpdateService schedulingUpdateService =
                new SchedulingUpdateService(publisher,taskReadinessRepository);

        GraphLock lock =
                PgRepos.graphLock();

        PriorityScoreService priorityScoreService =
                new PriorityScoreService(
                                taskRepository,
                                dependencyRepository,
                                taskReadinessRepository,
                                schedulingUpdateService,
                                new SchedulingPublishListener(
                                taskRepository,
                                schedulingUpdateService));

        service =
                new DependencyEdgeService(
                        taskRepository,
                        dependencyRepository,
                        lock,
                        priorityScoreService,
                        TaskStateHistoryFixtures.newRecorder(),
                        taskReadinessRepository);
    }

    @Test
    void addDependency_shouldIncreaseBlockingCountAndMakeTaskNotReady() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependencyId =
                UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        dependencyId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        TestTransactions.runCommitted(
                () -> service.addDependency(
                        taskId,
                        dependencyId));

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                1,
                blockingCount(updated));

        assertFalse(
                isReady(updated));

        assertEquals(
                1,
                publisher.getEvents().size());

        assertEquals(
                taskId,
                publisher.getEvents()
                        .get(0)
                        .getTaskId());

        assertFalse(
                publisher.getEvents()
                        .get(0)
                        .isAvailable());
    }

    @Test
    void addDependency_whenDependencyAlreadyDone_shouldNotBlockTask() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependencyId =
                UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        dependencyId,
                        TaskStatus.DONE,
                        true,
                        0,
                        0));

        service.addDependency(
                taskId,
                dependencyId);

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                0,
                blockingCount(updated));

        assertTrue(
                isReady(updated));

        assertTrue(
                publisher.getEvents().isEmpty());
    }

    @Test
    void addDependency_whenDuplicate_shouldThrowDuplicateDependencyException() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependencyId =
                UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        dependencyId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        service.addDependency(
                taskId,
                dependencyId);

        assertThrows(
                DuplicateDependencyException.class,
                () -> service.addDependency(
                        taskId,
                        dependencyId));

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                1,
                blockingCount(updated));
    }

    @Test
    void addDependency_whenCycleWouldBeCreated_shouldThrowInvalidDependencyException() {

        UUID taskA =
                UUID.randomUUID();

        UUID taskB =
                UUID.randomUUID();

        UUID taskC =
                UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskA,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        taskB,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        taskC,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        service.addDependency(
                taskA,
                taskB);

        service.addDependency(
                taskB,
                taskC);

        assertThrows(
                InvalidDependencyException.class,
                () -> service.addDependency(
                        taskC,
                        taskA));

        assertTrue(
                dependencyRepository
                        .findByTaskId(taskC)
                        .isEmpty());
    }

    @Test
    void removeDependency_shouldDecreaseBlockingCountAndMakeTaskReady() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependencyId =
                UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        dependencyId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        TestTransactions.runCommitted(
                () -> service.addDependency(
                        taskId,
                        dependencyId));

        publisher.clear();

        TestTransactions.runCommitted(
                () -> service.removeDependency(
                        taskId,
                        dependencyId));

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                0,
                blockingCount(updated));

        assertTrue(
                isReady(updated));

        assertEquals(
                1,
                publisher.getEvents().size());

        assertTrue(
                publisher.getEvents()
                        .get(0)
                        .isAvailable());
    }

    @Test
    void removeDependency_whenDependencyDoesNotExist_shouldThrow() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependencyId =
                UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        dependencyId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        assertThrows(
                DependencyNotFoundException.class,
                () -> service.removeDependency(
                        taskId,
                        dependencyId));
    }

    @Test
    void addDependency_incrementsBlockingCount_andStaysNotReady() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependsOnTaskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskId,
                                        0,
                                        0,
                                        true),
                                buildTask(
                                        dependsOnTaskId,
                                        0,
                                        0,
                                        true)),
                        List.of());

        fixture.service()
                .addDependency(
                        taskId,
                        dependsOnTaskId);

        assertTrue(
                fixture.dependencyRepo()
                        .findByTaskId(taskId)
                        .contains(
                                new TaskDependency(
                                        taskId,
                                        dependsOnTaskId)));

        Task task =
                fixture.taskRepo()
                        .findById(taskId)
                        .orElseThrow();

        assertEquals(
                1,
                blockingCount(task));

        assertFalse(
                isReady(task));
    }

    @Test
    void addSecondDependency_multipleBlockers() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependsOnTaskId =
                UUID.randomUUID();

        // The task already has one real blocker; the view counts rows, not a seeded counter.
        UUID existingBlockerId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskId,
                                        1,
                                        0,
                                        false),
                                buildTask(
                                        dependsOnTaskId,
                                        0,
                                        0,
                                        true),
                                buildTask(
                                        existingBlockerId,
                                        0,
                                        0,
                                        true)),
                        List.of(
                                new TaskDependency(
                                        taskId,
                                        existingBlockerId)));

        fixture.service()
                .addDependency(
                        taskId,
                        dependsOnTaskId);

        Task task =
                fixture.taskRepo()
                        .findById(taskId)
                        .orElseThrow();

        assertEquals(
                2,
                blockingCount(task));

        assertFalse(
                isReady(task));
    }

    @Test
    void addDependency_duplicate_doesNotInflateBlockingCount() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependsOnTaskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskId,
                                        1,
                                        0,
                                        false),
                                buildTask(
                                        dependsOnTaskId,
                                        0,
                                        0,
                                        true)),
                        List.of(
                                new TaskDependency(
                                        taskId,
                                        dependsOnTaskId)));

        assertThrows(
                DuplicateDependencyException.class,
                () -> fixture.service()
                        .addDependency(
                                taskId,
                                dependsOnTaskId));

        assertEquals(
                1,
                fixture.dependencyRepo()
                        .findByTaskId(taskId)
                        .size());

        assertEquals(
                1,
                blockingCount(fixture.taskRepo()
                        .findById(taskId)
                        .orElseThrow()));
    }

    @Test
    void addDependency_targetAlreadyDone_doesNotIncrementBlockingCount_butStillRecalculates() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependsOnTaskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskId,
                                        0,
                                        0,
                                        false),
                                buildTask(
                                        dependsOnTaskId,
                                        0,
                                        0,
                                        true,
                                        TaskStatus.DONE)),
                        List.of());

        fixture.service()
                .addDependency(
                        taskId,
                        dependsOnTaskId);

        assertTrue(
                fixture.dependencyRepo()
                        .findByTaskId(taskId)
                        .contains(
                                new TaskDependency(
                                        taskId,
                                        dependsOnTaskId)));

        Task task =
                fixture.taskRepo()
                        .findById(taskId)
                        .orElseThrow();

        assertEquals(
                0,
                blockingCount(task));

        assertTrue(
                isReady(task));
    }

    @Test
    void addDependency_selfDependency_throwsIllegalStateAndNeverSaves() {

        UUID taskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskId,
                                        0,
                                        0,
                                        true)),
                        List.of());

        assertThrows(
                InvalidDependencyException.class,
                () -> fixture.service()
                        .addDependency(
                                taskId,
                                taskId));

        assertTrue(
                fixture.dependencyRepo()
                        .findByTaskId(taskId)
                        .isEmpty());
    }

    @Test
    void addDependency_wouldCreateCycle_throwsAndLeavesStateUnchanged() {

        UUID taskA =
                UUID.randomUUID();

        UUID taskB =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskA,
                                        0,
                                        0,
                                        true),
                                buildTask(
                                        taskB,
                                        0,
                                        0,
                                        true)),
                        List.of(
                                new TaskDependency(
                                        taskA,
                                        taskB)));

        assertThrows(
                InvalidDependencyException.class,
                () -> fixture.service()
                        .addDependency(
                                taskB,
                                taskA));

        assertEquals(
                1,
                fixture.dependencyRepo()
                        .findByTaskId(taskA)
                        .size());

        assertTrue(
                fixture.dependencyRepo()
                        .findByTaskId(taskB)
                        .isEmpty());
    }

    @Test
    void addDependency_taskNotFound_throwsAndLeavesStateUnchanged() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependsOnTaskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        dependsOnTaskId,
                                        0,
                                        0,
                                        true)),
                        List.of());

        assertThrows(
                TaskNotFoundException.class,
                () -> fixture.service()
                        .addDependency(
                                taskId,
                                dependsOnTaskId));

        assertTrue(
                fixture.dependencyRepo()
                        .findByTaskId(taskId)
                        .isEmpty());
    }

    @Test
    void addDependency_dependsOnTaskNotFound_throwsAndLeavesStateUnchanged() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependsOnTaskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(),
                        List.of());

        assertThrows(
                TaskNotFoundException.class,
                () -> fixture.service()
                        .addDependency(
                                taskId,
                                dependsOnTaskId));

        assertTrue(
                fixture.dependencyRepo()
                        .findByTaskId(taskId)
                        .isEmpty());
    }

    @Test
    void addDependencies_appliesEveryValidEntry() {

        UUID taskId =
                UUID.randomUUID();

        UUID depA =
                UUID.randomUUID();

        UUID depB =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskId,
                                        0,
                                        0,
                                        true),
                                buildTask(
                                        depA,
                                        0,
                                        0,
                                        true),
                                buildTask(
                                        depB,
                                        0,
                                        0,
                                        true)),
                        List.of());

        fixture.service()
                .addDependencies(
                        taskId,
                        List.of(
                                depA,
                                depB));

        assertEquals(
                2,
                fixture.dependencyRepo()
                        .findByTaskId(taskId)
                        .size());

        Task task =
                fixture.taskRepo()
                        .findById(taskId)
                        .orElseThrow();

        assertEquals(
                2,
                blockingCount(task));

        assertFalse(
                isReady(task));
    }

    @Test
    void addDependencies_oneCycleAmongValidEntries_appliesNoneOfThem() {

        UUID taskA =
                UUID.randomUUID();

        UUID taskB =
                UUID.randomUUID();

        UUID taskC =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskA,
                                        0,
                                        0,
                                        true),
                                buildTask(
                                        taskB,
                                        0,
                                        0,
                                        true),
                                buildTask(
                                        taskC,
                                        0,
                                        0,
                                        true)),
                        List.of(
                                new TaskDependency(
                                        taskA,
                                        taskB)));

        assertThrows(
                InvalidDependencyException.class,
                () -> fixture.service()
                        .addDependencies(
                                taskB,
                                List.of(
                                        taskC,
                                        taskA)));

        assertTrue(
                fixture.dependencyRepo()
                        .findByTaskId(taskB)
                        .isEmpty(),
                "the valid entry earlier in the batch must not be applied when a later entry fails");

        assertEquals(
                0,
                blockingCount(fixture.taskRepo()
                        .findById(taskB)
                        .orElseThrow()));
    }

    @Test
    void addDependencies_oneMissingDependsOnTaskAmongValidEntries_appliesNoneOfThem() {

        UUID taskId =
                UUID.randomUUID();

        UUID validDep =
                UUID.randomUUID();

        UUID missingDep =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskId,
                                        0,
                                        0,
                                        true),
                                buildTask(
                                        validDep,
                                        0,
                                        0,
                                        true)),
                        List.of());

        assertThrows(
                TaskNotFoundException.class,
                () -> fixture.service()
                        .addDependencies(
                                taskId,
                                List.of(
                                        validDep,
                                        missingDep)));

        assertTrue(
                fixture.dependencyRepo()
                        .findByTaskId(taskId)
                        .isEmpty(),
                "the valid entry earlier in the batch must not be applied when a later entry fails");
    }

    @Test
    void addDependencies_duplicateIdInSameBatch_countsOnce() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependsOnTaskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskId,
                                        0,
                                        0,
                                        true),
                                buildTask(
                                        dependsOnTaskId,
                                        0,
                                        0,
                                        true)),
                        List.of());

        fixture.service()
                .addDependencies(
                        taskId,
                        List.of(
                                dependsOnTaskId,
                                dependsOnTaskId));

        assertEquals(
                1,
                fixture.dependencyRepo()
                        .findByTaskId(taskId)
                        .size());

        assertEquals(
                1,
                blockingCount(fixture.taskRepo()
                        .findById(taskId)
                        .orElseThrow()),
                "a duplicate id within one batch must count as a single edge, not double the blockingCount");
    }

    @Test
    void addDependency_nullTaskId_throwsNpe() {

        UUID dependsOnTaskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(),
                        List.of());

        assertThrows(
                NullPointerException.class,
                () -> fixture.service()
                        .addDependency(
                                null,
                                dependsOnTaskId));
    }

    @Test
    void addDependency_nullDependsOnTaskId_throwsNpe() {

        UUID taskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(),
                        List.of());

        assertThrows(
                NullPointerException.class,
                () -> fixture.service()
                        .addDependency(
                                taskId,
                                null));
    }

    @Test
    void removeDependency_decrementsBlockingCount_becomesReady() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependsOnTaskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskId,
                                        1,
                                        0,
                                        false),
                                buildTask(
                                        dependsOnTaskId,
                                        0,
                                        0,
                                        true)),
                        List.of(
                                new TaskDependency(
                                        taskId,
                                        dependsOnTaskId)));

        fixture.service()
                .removeDependency(
                        taskId,
                        dependsOnTaskId);

        assertTrue(
                fixture.dependencyRepo()
                        .findByTaskId(taskId)
                        .isEmpty());

        Task task =
                fixture.taskRepo()
                        .findById(taskId)
                        .orElseThrow();

        assertEquals(
                0,
                blockingCount(task));

        assertTrue(
                isReady(task));
    }

    @Test
    void removeOneOfMultipleBlockers_staysNotReady() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependsOnTaskId =
                UUID.randomUUID();

        UUID otherBlockerId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskId,
                                        2,
                                        0,
                                        false),
                                buildTask(
                                        dependsOnTaskId,
                                        0,
                                        0,
                                        true),
                                buildTask(
                                        otherBlockerId,
                                        0,
                                        0,
                                        true)),
                        List.of(
                                new TaskDependency(
                                        taskId,
                                        dependsOnTaskId),
                                new TaskDependency(
                                        taskId,
                                        otherBlockerId)));

        fixture.service()
                .removeDependency(
                        taskId,
                        dependsOnTaskId);

        assertEquals(
                List.of(otherBlockerId),
                fixture.dependencyRepo()
                        .findByTaskId(taskId)
                        .stream()
                        .map(TaskDependency::dependsOnTaskId)
                        .toList());

        Task task =
                fixture.taskRepo()
                        .findById(taskId)
                        .orElseThrow();

        assertEquals(
                1,
                blockingCount(task));

        assertFalse(
                isReady(task));
    }

    @Test
    void removeDependency_targetAlreadyDone_doesNotDecrementBlockingCount() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependsOnTaskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskId,
                                        0,
                                        0,
                                        true),
                                buildTask(
                                        dependsOnTaskId,
                                        0,
                                        0,
                                        true,
                                        TaskStatus.DONE)),
                        List.of(
                                new TaskDependency(
                                        taskId,
                                        dependsOnTaskId)));

        fixture.service()
                .removeDependency(
                        taskId,
                        dependsOnTaskId);

        assertEquals(
                0,
                blockingCount(fixture.taskRepo()
                        .findById(taskId)
                        .orElseThrow()));
    }

    @Test
    void removeDependency_notFound_doesNotChangeCounter() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependsOnTaskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskId,
                                        0,
                                        0,
                                        true),
                                buildTask(
                                        dependsOnTaskId,
                                        0,
                                        0,
                                        true)),
                        List.of());

        assertThrows(
                DependencyNotFoundException.class,
                () -> fixture.service()
                        .removeDependency(
                                taskId,
                                dependsOnTaskId));

        assertTrue(
                fixture.dependencyRepo()
                        .findByTaskId(taskId)
                        .isEmpty());

        assertEquals(
                0,
                blockingCount(fixture.taskRepo()
                        .findById(taskId)
                        .orElseThrow()));
    }

    @Test
    void removeDependency_blockingCountNeverGoesNegative() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependsOnTaskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskId,
                                        0,
                                        0,
                                        true),
                                buildTask(
                                        dependsOnTaskId,
                                        0,
                                        0,
                                        true)),
                        List.of(
                                new TaskDependency(
                                        taskId,
                                        dependsOnTaskId)));

        fixture.service()
                .removeDependency(
                        taskId,
                        dependsOnTaskId);

        assertEquals(
                0,
                blockingCount(fixture.taskRepo()
                        .findById(taskId)
                        .orElseThrow()));
    }

    @Test
    void removeDependency_nullTaskId_throwsNpe() {

        UUID dependsOnTaskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(),
                        List.of());

        assertThrows(
                NullPointerException.class,
                () -> fixture.service()
                        .removeDependency(
                                null,
                                dependsOnTaskId));
    }

    @Test
    void removeDependency_nullDependsOnTaskId_throwsNpe() {

        UUID taskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(),
                        List.of());

        assertThrows(
                NullPointerException.class,
                () -> fixture.service()
                        .removeDependency(
                                taskId,
                                null));
    }

    @Test
    void addDependency_differentTenants_throwsCrossTenantDependencyException() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependsOnTaskId =
                UUID.randomUUID();

        Task task =
                buildTask(
                        taskId,
                        0,
                        0,
                        true);

        Task dependsOnTask =
                TaskTestFixtures.defaultTask(dependsOnTaskId)
                        .tenantId(TaskTestFixtures.OTHER_TENANT_ID)
                        .build();

        Fixture fixture =
                newFixture(
                        List.of(
                                task,
                                dependsOnTask),
                        List.of());

        assertThrows(
                CrossTenantDependencyException.class,
                () -> fixture.service()
                        .addDependency(
                                taskId,
                                dependsOnTaskId));

        assertTrue(
                fixture.dependencyRepo()
                        .findByTaskId(taskId)
                        .isEmpty(),
                "a rejected edge must never be written");
    }

    @Test
    void addDependency_eitherTenantNull_throwsCrossTenantDependencyException() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependsOnTaskId =
                UUID.randomUUID();

        Task task =
                TaskTestFixtures.defaultTask(taskId)
                        .tenantId(null)
                        .build();

        Task dependsOnTask =
                buildTask(
                        dependsOnTaskId,
                        0,
                        0,
                        true);

        Fixture fixture =
                newFixture(
                        List.of(
                                task,
                                dependsOnTask),
                        List.of());

        assertThrows(
                CrossTenantDependencyException.class,
                () -> fixture.service()
                        .addDependency(
                                taskId,
                                dependsOnTaskId));
    }

    @Test
    void addDependency_sameTenant_isAllowed() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependsOnTaskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskId,
                                        0,
                                        0,
                                        true),
                                buildTask(
                                        dependsOnTaskId,
                                        0,
                                        0,
                                        true)),
                        List.of());

        fixture.service()
                .addDependency(
                        taskId,
                        dependsOnTaskId);

        assertEquals(
                1,
                blockingCount(fixture.taskRepo()
                        .findById(taskId)
                        .orElseThrow()));
    }

    @Test
    void addDependencies_oneEntryCrossTenant_throwsAndAppliesNoneOfTheBatch() {

        UUID taskId =
                UUID.randomUUID();

        UUID sameTenantDep =
                UUID.randomUUID();

        UUID crossTenantDep =
                UUID.randomUUID();

        Task crossTenantTask =
                TaskTestFixtures.defaultTask(crossTenantDep)
                        .tenantId(TaskTestFixtures.OTHER_TENANT_ID)
                        .build();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskId,
                                        0,
                                        0,
                                        true),
                                buildTask(
                                        sameTenantDep,
                                        0,
                                        0,
                                        true),
                                crossTenantTask),
                        List.of());

        assertThrows(
                CrossTenantDependencyException.class,
                () -> fixture.service()
                        .addDependencies(
                                taskId,
                                List.of(
                                        sameTenantDep,
                                        crossTenantDep)));

        assertTrue(
                fixture.dependencyRepo()
                        .findByTaskId(taskId)
                        .isEmpty(),
                "the whole batch must be rejected, including the otherwise-valid same-tenant entry");
    }

    private Task createTask(
            UUID id,
            TaskStatus status,
            boolean ready,
            int blockingCount,
            int openConstraintCount) {

        LocalDateTime now =
                LocalDateTime.now();

        return Task.builder()
                .id(id)
                .parentTaskId(null)
                .tenantId(TaskTestFixtures.DEFAULT_TENANT_ID)
                .status(status)
                .estimatedDurationMin(null)
                .score(null)
                .createdAt(now)
                .updatedAt(now)
                .deletedAt(null)
                .build();
    }

    private static class RecordingSchedulingUpdatePublisher
            implements SchedulingUpdatePublisher {

        private final List<TaskSchedulingUpdatedEvent> events =
                new ArrayList<>();

        @Override
        public void publish(
                TaskSchedulingUpdatedEvent event) {

            events.add(event);
        }

        List<TaskSchedulingUpdatedEvent> getEvents() {
            return events;
        }

        void clear() {
            events.clear();
        }
    }

    private record Fixture(
            TaskRepository taskRepo,
            TaskDependencyRepository dependencyRepo,
            ConstraintRepository constraintRepo,
            DependencyEdgeService service) {
    }

    private Fixture newFixture(
            List<Task> tasks,
            List<TaskDependency> edges) {

        TaskRepository taskRepo =
                PgRepos.tasks(tasks);

        TaskDependencyRepository dependencyRepo =
                PgRepos.dependencies(edges);

        ConstraintRepository constraintRepo =
                PgRepos.constraints();

        SchedulingUpdateService scheduling =
                new SchedulingUpdateService(event -> {},taskReadinessRepository);

        GraphLock lock =
                PgRepos.graphLock();

        PriorityScoreService priorityScoreService =
                new PriorityScoreService(
                                taskRepo,
                                dependencyRepo,
                                taskReadinessRepository,
                                scheduling,
                                new SchedulingPublishListener(
                                taskRepo,
                                scheduling));

        DependencyEdgeService edgeService =
                new DependencyEdgeService(
                        taskRepo,
                        dependencyRepo,
                        lock,
                        priorityScoreService,
                        TaskStateHistoryFixtures.newRecorder(),
                        taskReadinessRepository);

        return new Fixture(
                taskRepo,
                dependencyRepo,
                constraintRepo,
                edgeService);
    }

    private static Task buildTask(
            UUID id,
            int blockingCount,
            int openConstraintCount,
            boolean isReady) {

        return buildTask(
                id,
                blockingCount,
                openConstraintCount,
                isReady,
                TaskStatus.ACTIVE);
    }

    private static Task buildTask(
            UUID id,
            int blockingCount,
            int openConstraintCount,
            boolean isReady,
            TaskStatus status) {

        return TaskTestFixtures.defaultTask(id)
                .status(status)
                .build();
    }
}