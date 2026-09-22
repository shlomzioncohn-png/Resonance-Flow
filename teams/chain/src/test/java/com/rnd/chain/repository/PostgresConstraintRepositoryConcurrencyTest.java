package com.rnd.chain.repository;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.testsupport.ConcurrentTestRunner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link PostgresConstraintRepository}'s concurrency guarantees against a real Postgres
 * instance - same "cannot pass yet" caveat as {@link PostgresConstraintRepositoryTest} (see its
 * Javadoc): no {@code task(id)} table exists until issue #377 lands.
 *
 * <p>{@code TaskRepository#update} already gives {@code Task.openConstraintCount} atomic per-id
 * updates once {@link ConstraintRepository} has told the caller what actually happened at the DB
 * (see {@code TaskEligibilityService.onConstraintAdded}/{@code onConstraintStatusChanged}) - what
 * this class verifies is that {@link ConstraintRepository} itself reports that DB-verified fact
 * correctly even when multiple threads race on the exact same {@code constraintId}, which a
 * single-threaded test can't exercise.
 */
@SpringBootTest
class PostgresConstraintRepositoryConcurrencyTest {

    private static final int THREAD_COUNT = 16;

    @Autowired
    private ConstraintRepository constraintRepository;

    private Constraint newConstraint(String constraintId, UUID taskId, Constraint.ConstraintStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new Constraint(constraintId, taskId, Constraint.ConstraintType.APPROVAL_NEEDED,
                status, null, "source-ref", now, now);
    }

    @Test
    void save_sameConstraintIdFromManyThreads_exactlyOneReportsInsert() throws InterruptedException {
        // Simulates the same "constraint added" event redelivered concurrently (e.g. two
        // partitions, or a retry racing the original delivery) - onConstraintAdded relies on
        // exactly one of these being true to increment openConstraintCount exactly once.
        String constraintId = UUID.randomUUID().toString();
        UUID taskId = UUID.randomUUID();
        Queue<Boolean> results = new ConcurrentLinkedQueue<>();

        ConcurrentTestRunner.run(THREAD_COUNT, index ->
                results.add(constraintRepository.save(
                        newConstraint(constraintId, taskId, Constraint.ConstraintStatus.PENDING))));

        assertThat(results).hasSize(THREAD_COUNT);
        assertThat(results.stream().filter(Boolean::booleanValue).count())
                .as("exactly one concurrent save() of the same constraintId should report a genuine insert")
                .isEqualTo(1L);
        assertThat(constraintRepository.findByTaskId(taskId)).hasSize(1);
    }

    @Test
    void updateStatus_sameConstraintIdFromManyThreads_exactlyOneObservesTheRealTransition()
            throws InterruptedException {
        String constraintId = UUID.randomUUID().toString();
        UUID taskId = UUID.randomUUID();
        constraintRepository.save(newConstraint(constraintId, taskId, Constraint.ConstraintStatus.PENDING));
        Queue<Constraint.ConstraintStatus> previousStatuses = new ConcurrentLinkedQueue<>();

        // All threads race to apply the exact same transition (PENDING -> SATISFIED), simulating
        // the same status-change event redelivered concurrently. FOR UPDATE serializes them, so
        // only the first to actually run the update should see the real previous status
        // (PENDING); every other thread, once it acquires the row lock after that, finds the row
        // already SATISFIED and correctly reports that as "previous" - a safe no-op signal to
        // TaskEligibilityService, not a lost update or a duplicate counter decrement.
        ConcurrentTestRunner.run(THREAD_COUNT, index ->
                previousStatuses.add(constraintRepository.updateStatus(
                        constraintId, Constraint.ConstraintStatus.SATISFIED, null, LocalDateTime.now())));

        assertThat(previousStatuses).hasSize(THREAD_COUNT);
        assertThat(previousStatuses.stream()
                .filter(status -> status == Constraint.ConstraintStatus.PENDING).count())
                .as("exactly one concurrent updateStatus() should observe the real PENDING -> SATISFIED transition")
                .isEqualTo(1L);
        assertThat(previousStatuses.stream()
                .filter(status -> status == Constraint.ConstraintStatus.SATISFIED).count())
                .isEqualTo((long) THREAD_COUNT - 1);
    }
}
