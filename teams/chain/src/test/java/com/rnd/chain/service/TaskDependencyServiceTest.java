package com.rnd.chain.service;

import com.rnd.chain.dto.DependencyChangeAction;
import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;
import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.exception.DependencyNotFoundException;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.DependencyHistoryRepository;
import com.rnd.chain.repository.InMemoryDependencyHistoryRepository;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.repository.InMemoryTransactionRunner;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.repository.TransactionRunner;
import com.rnd.chain.testsupport.ConcurrentTestRunner;
import com.rnd.chain.testsupport.IncrementingClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskDependencyServiceTest {

    private UUID taskA;
    private UUID taskB;
    private UUID taskC;
    private UUID taskD;
    private UUID unknownTaskId;

    private TaskRepository taskRepository;
    private TaskDependencyRepository taskDependencyRepository;
    private DependencyHistoryRepository historyRepository;
    private TransactionRunner transactionRunner;
    private TaskDependencyService service;

    @BeforeEach
    void setUp() {
        taskA = UUID.randomUUID();
        taskB = UUID.randomUUID();
        taskC = UUID.randomUUID();
        taskD = UUID.randomUUID();
        unknownTaskId = UUID.randomUUID();

        taskRepository = new InMemoryTaskRepository(
                List.of(newTask(taskA), newTask(taskB), newTask(taskC), newTask(taskD)));
        taskDependencyRepository = new InMemoryTaskDependencyRepository(List.of());
        historyRepository = new InMemoryDependencyHistoryRepository();
        transactionRunner = new InMemoryTransactionRunner();

        service = new TaskDependencyService(taskRepository, taskDependencyRepository,
                historyRepository, transactionRunner, new IncrementingClock());
    }

    // --- constructor -----------------------------------------------------

    @Test
    void constructor_throwsNullPointerException_whenTaskRepositoryIsNull() {
        TaskDependencyRepository taskDependencyRepository = new InMemoryTaskDependencyRepository(List.of());

        assertThatThrownBy(() -> new TaskDependencyService(null, taskDependencyRepository))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("taskRepository");
    }

    @Test
    void constructor_throwsNullPointerException_whenTaskDependencyRepositoryIsNull() {
        TaskRepository taskRepository = new InMemoryTaskRepository(List.of());

        assertThatThrownBy(() -> new TaskDependencyService(taskRepository, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("taskDependencyRepository");
    }

    // --- addDependency -------------------------------------------------

    @Test
    void addDependency_createsEdge_retrievableAsDependencyAndDependent() {
        service.addDependency(taskA, taskB);

        assertThat(service.getDependencies(taskA)).containsExactly(taskB);
        assertThat(service.getDependents(taskB)).containsExactly(taskA);
    }

    @Test
    void addDependency_rejectsSelfDependency() {
        assertThatThrownBy(() -> service.addDependency(taskA, taskA))
                .isInstanceOf(InvalidDependencyException.class);
    }

    @Test
    void addDependency_rejectsWhenTaskDoesNotExist() {
        assertThatThrownBy(() -> service.addDependency(unknownTaskId, taskB))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void addDependency_rejectsWhenDependsOnTaskDoesNotExist() {
        assertThatThrownBy(() -> service.addDependency(taskA, unknownTaskId))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void addDependency_rejectsDuplicateEdge() {
        service.addDependency(taskA, taskB);

        assertThatThrownBy(() -> service.addDependency(taskA, taskB))
                .isInstanceOf(DuplicateDependencyException.class);
    }

    @Test
    void addDependency_underConcurrentDuplicateAttempts_succeedsExactlyOnce() throws InterruptedException {
        int threadCount = 20;
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger duplicateRejections = new AtomicInteger();

        ConcurrentTestRunner.run(threadCount, () -> {
            try {
                service.addDependency(taskA, taskB);
                successes.incrementAndGet();
            } catch (DuplicateDependencyException e) {
                duplicateRejections.incrementAndGet();
            }
        });

        assertThat(successes.get()).isEqualTo(1);
        assertThat(duplicateRejections.get()).isEqualTo(threadCount - 1);
        assertThat(service.getDependencies(taskA)).containsExactly(taskB);
    }

    // --- removeDependency ------------------------------------------------

    @Test
    void removeDependency_removesEdge() {
        service.addDependency(taskA, taskB);

        service.removeDependency(taskA, taskB);

        assertThat(service.getDependencies(taskA)).isEmpty();
        assertThat(service.getDependents(taskB)).isEmpty();
    }

    @Test
    void removeDependency_rejectsWhenEdgeDoesNotExist() {
        assertThatThrownBy(() -> service.removeDependency(taskA, taskB))
                .isInstanceOf(DependencyNotFoundException.class);
    }

    @Test
    void removeDependency_underConcurrentDuplicateAttempts_succeedsExactlyOnce() throws InterruptedException {
        service.addDependency(taskA, taskB);
        int threadCount = 20;
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger notFoundRejections = new AtomicInteger();

        ConcurrentTestRunner.run(threadCount, () -> {
            try {
                service.removeDependency(taskA, taskB);
                successes.incrementAndGet();
            } catch (DependencyNotFoundException e) {
                notFoundRejections.incrementAndGet();
            }
        });

        assertThat(successes.get()).isEqualTo(1);
        assertThat(notFoundRejections.get()).isEqualTo(threadCount - 1);
        assertThat(service.getDependencies(taskA)).isEmpty();
    }

    // --- getDependencies / getDependents ----------------------------------

    @Test
    void getDependencies_rejectsWhenTaskDoesNotExist() {
        assertThatThrownBy(() -> service.getDependencies(unknownTaskId))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void getDependents_rejectsWhenTaskDoesNotExist() {
        assertThatThrownBy(() -> service.getDependents(unknownTaskId))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void getDependencies_isEmpty_forTaskWithNoDependencies() {
        assertThat(service.getDependencies(taskA)).isEmpty();
    }

    @Test
    void getDependents_isEmpty_forTaskNothingDependsOn() {
        assertThat(service.getDependents(taskA)).isEmpty();
    }

    // --- reads wait for in-flight writes, so they can't see a mid-rollback state ---

    @Test
    void getDependencies_blocksUntilAnInFlightTransactionFinishes() throws InterruptedException {
        CountDownLatch transactionStarted = new CountDownLatch(1);
        CountDownLatch releaseTransaction = new CountDownLatch(1);

        Thread holder = new Thread(() -> transactionRunner.runInTransaction(() -> {
            transactionStarted.countDown();
            awaitUpTo2Seconds(releaseTransaction);
            return null;
        }));
        holder.start();
        assertThat(transactionStarted.await(2, TimeUnit.SECONDS)).isTrue();

        AtomicBoolean readReturned = new AtomicBoolean(false);
        Thread reader = new Thread(() -> {
            service.getDependencies(taskA);
            readReturned.set(true);
        });
        reader.start();

        Thread.sleep(200);
        assertThat(readReturned.get()).isFalse();

        releaseTransaction.countDown();
        reader.join(2000);
        assertThat(readReturned.get()).isTrue();
        holder.join(2000);
    }

    @Test
    void getDependents_blocksUntilAnInFlightTransactionFinishes() throws InterruptedException {
        CountDownLatch transactionStarted = new CountDownLatch(1);
        CountDownLatch releaseTransaction = new CountDownLatch(1);

        Thread holder = new Thread(() -> transactionRunner.runInTransaction(() -> {
            transactionStarted.countDown();
            awaitUpTo2Seconds(releaseTransaction);
            return null;
        }));
        holder.start();
        assertThat(transactionStarted.await(2, TimeUnit.SECONDS)).isTrue();

        AtomicBoolean readReturned = new AtomicBoolean(false);
        Thread reader = new Thread(() -> {
            service.getDependents(taskA);
            readReturned.set(true);
        });
        reader.start();

        Thread.sleep(200);
        assertThat(readReturned.get()).isFalse();

        releaseTransaction.countDown();
        reader.join(2000);
        assertThat(readReturned.get()).isTrue();
        holder.join(2000);
    }

    private static void awaitUpTo2Seconds(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Matches the example from issue #66:
     * A depends on B, A depends on C, D depends on A
     * -> getDependencies(A) = [B, C], getDependents(A) = [D]
     */
    @Test
    void matchesDependencyGraphExampleFromIssue() {
        service.addDependency(taskA, taskB);
        service.addDependency(taskA, taskC);
        service.addDependency(taskD, taskA);

        assertThat(service.getDependencies(taskA)).containsExactlyInAnyOrder(taskB, taskC);
        assertThat(service.getDependents(taskA)).containsExactly(taskD);
    }

    // --- history: recorded on success only --------------------------------

    @Test
    void addDependency_success_recordsOneHistoryEntry_withActionAdded() {
        service.addDependency(taskA, taskB);

        List<DependencyHistoryEntry> entries = service.getHistory(taskA, 0, 20).getContent();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getAction()).isEqualTo(DependencyChangeAction.ADDED);
    }

    @Test
    void removeDependency_success_recordsOneHistoryEntry_withActionRemoved() {
        service.addDependency(taskA, taskB);

        service.removeDependency(taskA, taskB);

        List<DependencyHistoryEntry> entries = service.getHistory(taskA, 0, 20).getContent();
        assertThat(entries).extracting(DependencyHistoryEntry::getAction)
                .containsExactly(DependencyChangeAction.REMOVED, DependencyChangeAction.ADDED);
    }

    @Test
    void addDependency_failedValidation_recordsNoHistoryEntry() {
        assertThatThrownBy(() -> service.addDependency(taskA, taskA))
                .isInstanceOf(InvalidDependencyException.class);

        assertThat(service.getHistory(taskA, 0, 20).getContent()).isEmpty();
    }

    @Test
    void addDependency_duplicateEdge_recordsNoAdditionalHistoryEntry() {
        service.addDependency(taskA, taskB);

        assertThatThrownBy(() -> service.addDependency(taskA, taskB))
                .isInstanceOf(DuplicateDependencyException.class);

        assertThat(service.getHistory(taskA, 0, 20).getContent()).hasSize(1);
    }

    // --- rollback: history-write failure undoes the graph mutation ---------

    @Test
    void addDependency_historyWriteFails_edgeIsRolledBack() {
        DependencyHistoryRepository failingHistoryRepository = new ThrowingDependencyHistoryRepository();
        TaskRepository taskRepository = new InMemoryTaskRepository(
                List.of(newTask(taskA), newTask(taskB)));
        TaskDependencyService serviceWithFailingHistory = new TaskDependencyService(
                taskRepository, taskDependencyRepository, failingHistoryRepository,
                new InMemoryTransactionRunner(), new IncrementingClock());

        assertThatThrownBy(() -> serviceWithFailingHistory.addDependency(taskA, taskB))
                .isInstanceOf(RuntimeException.class);

        assertThat(taskDependencyRepository.findByTaskId(taskA)).isEmpty();
    }

    @Test
    void removeDependency_historyWriteFails_edgeIsRestored() {
        service.addDependency(taskA, taskB);
        DependencyHistoryRepository failingHistoryRepository = new ThrowingDependencyHistoryRepository();
        TaskRepository taskRepository = new InMemoryTaskRepository(
                List.of(newTask(taskA), newTask(taskB)));
        TaskDependencyService serviceWithFailingHistory = new TaskDependencyService(
                taskRepository, taskDependencyRepository, failingHistoryRepository,
                new InMemoryTransactionRunner(), new IncrementingClock());

        assertThatThrownBy(() -> serviceWithFailingHistory.removeDependency(taskA, taskB))
                .isInstanceOf(RuntimeException.class);

        assertThat(taskDependencyRepository.findByTaskId(taskA))
                .extracting(TaskDependency::getDependsOnTaskId)
                .containsExactly(taskB);
    }

    // --- sourceRef required for MIND/SYSTEM, optional for USER -------------

    @Test
    void addDependency_mindSourceWithoutSourceRef_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.addDependency(taskA, taskB, DependencyChangeSource.MIND, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addDependency_systemSourceWithBlankSourceRef_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.addDependency(taskA, taskB, DependencyChangeSource.SYSTEM, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addDependency_userSourceWithoutSourceRef_isAllowed() {
        service.addDependency(taskA, taskB, DependencyChangeSource.USER, null);

        assertThat(service.getDependencies(taskA)).containsExactly(taskB);
    }

    // --- idempotency retry ---------------------------------------------------

    @Test
    void addDependency_retryWithSameSourceRef_returnsWithoutDuplicateException() {
        service.addDependency(taskA, taskB, DependencyChangeSource.MIND, "evt-1");

        service.addDependency(taskA, taskB, DependencyChangeSource.MIND, "evt-1");

        assertThat(service.getHistory(taskA, 0, 20).getContent()).hasSize(1);
    }

    @Test
    void addDependency_sourceRefReusedForDifferentTaskPair_throwsInsteadOfFabricatingSuccess() {
        service.addDependency(taskA, taskB, DependencyChangeSource.MIND, "evt-1");

        assertThatThrownBy(() -> service.addDependency(taskC, taskD, DependencyChangeSource.MIND, "evt-1"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(service.getDependencies(taskC)).isEmpty();
    }

    @Test
    void removeDependency_sourceRefReusedForOppositeAction_throwsInsteadOfFabricatingSuccess() {
        service.addDependency(taskA, taskB, DependencyChangeSource.MIND, "evt-1");

        assertThatThrownBy(() -> service.removeDependency(taskA, taskB, DependencyChangeSource.MIND, "evt-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addDependency_retryAfterTaskWasDeleted_throwsTaskNotFoundException() {
        service.addDependency(taskA, taskB, DependencyChangeSource.MIND, "evt-1");
        taskRepository.save(Task.builder()
                .id(taskA)
                .ownerId("owner")
                .status(TaskStatus.ACTIVE)
                .isReady(true)
                .blockingCount(0)
                .openConstraintCount(0)
                .createdAt(LocalDateTime.now())
                .deletedAt(LocalDateTime.now())
                .build());

        assertThatThrownBy(() -> service.addDependency(taskA, taskB, DependencyChangeSource.MIND, "evt-1"))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void addDependency_concurrentRetriesWithSameSourceRef_allSucceed_oneHistoryEntry() throws InterruptedException {
        int threadCount = 20;
        AtomicInteger successes = new AtomicInteger();

        ConcurrentTestRunner.run(threadCount, () -> {
            service.addDependency(taskA, taskB, DependencyChangeSource.MIND, "evt-2");
            successes.incrementAndGet();
        });

        assertThat(successes.get()).isEqualTo(threadCount);
        assertThat(service.getHistory(taskA, 0, 20).getContent()).hasSize(1);
        assertThat(service.getDependencies(taskA)).containsExactly(taskB);
    }

    @Test
    void addDependency_userSourceWithoutSourceRef_hasNoIdempotencyProtection() {
        service.addDependency(taskA, taskB, DependencyChangeSource.USER, null);

        assertThatThrownBy(() -> service.addDependency(taskA, taskB, DependencyChangeSource.USER, null))
                .isInstanceOf(DuplicateDependencyException.class);
    }

    // --- getHistory ----------------------------------------------------------

    @Test
    void getHistory_rejectsWhenTaskDoesNotExist() {
        assertThatThrownBy(() -> service.getHistory(unknownTaskId, 0, 20))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void getHistory_isEmptyPage_notError_whenTaskExistsWithNoChanges() {
        PageResponse<DependencyHistoryEntry> page = service.getHistory(taskA, 0, 20);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(0);
    }

    /** Always throws on record() - used to prove the compensating rollback runs. */
    private static class ThrowingDependencyHistoryRepository implements DependencyHistoryRepository {
        @Override
        public DependencyHistoryEntry record(DependencyHistoryEntry entry) {
            throw new RuntimeException("simulated history write failure");
        }

        @Override
        public Optional<DependencyHistoryEntry> findBySourceAndSourceRef(DependencyChangeSource source, String sourceRef) {
            return Optional.empty();
        }

        @Override
        public PageResponse<DependencyHistoryEntry> findByTaskId(UUID taskId, int page, int size) {
            return new PageResponse<>(List.of(), page, size, 0, 0);
        }
    }

    private static Task newTask(UUID id) {
        return Task.builder()
                .id(id)
                .ownerId("owner")
                .status(TaskStatus.ACTIVE)
                .blockingCount(0)
                .openConstraintCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
