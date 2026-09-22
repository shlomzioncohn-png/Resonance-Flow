package com.rnd.chain.repository;

import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.testsupport.ConcurrentTestRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTaskDependencyRepositoryTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    private InMemoryTaskDependencyRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTaskDependencyRepository(List.of());
    }

    @Test
    void findByTaskId_returnsEmptyList_whenTaskHasNoDependencies() {
        assertThat(repository.findByTaskId(a)).isEmpty();
    }

    @Test
    void findByDependsOnTaskId_returnsEmptyList_whenNothingDependsOnTask() {
        assertThat(repository.findByDependsOnTaskId(a)).isEmpty();
    }

    @Test
    void save_persistsEdge_retrievableFromBothSides() {
        Optional<TaskDependency> result = repository.save(new TaskDependency(a, b));

        assertThat(result).contains(new TaskDependency(a, b));
        assertThat(repository.findByTaskId(a))
                .extracting(TaskDependency::dependsOnTaskId)
                .containsExactly(b);
        assertThat(repository.findByDependsOnTaskId(b))
                .extracting(TaskDependency::taskId)
                .containsExactly(a);
    }

    @Test
    void save_isIdempotent_forTheSameEdgeSavedTwice() {
        Optional<TaskDependency> first = repository.save(new TaskDependency(a, b));
        Optional<TaskDependency> second = repository.save(new TaskDependency(a, b));

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        assertThat(repository.findByTaskId(a)).hasSize(1);
    }

    @Test
    void save_supportsMultipleDependenciesForSameTask() {
        repository.save(new TaskDependency(a, b));
        repository.save(new TaskDependency(a, c));

        assertThat(repository.findByTaskId(a))
                .extracting(TaskDependency::dependsOnTaskId)
                .containsExactlyInAnyOrder(b, c);
    }

    @Test
    void delete_removesEdge_andReturnsTrue() {
        repository.save(new TaskDependency(a, b));

        boolean removed = repository.delete(a, b);

        assertThat(removed).isTrue();
        assertThat(repository.findByTaskId(a)).isEmpty();
        assertThat(repository.findByDependsOnTaskId(b)).isEmpty();
    }

    @Test
    void delete_onEdgeThatDoesNotExist_isANoOp_andReturnsFalse() {
        boolean removed = repository.delete(a, b);

        assertThat(removed).isFalse();
        assertThat(repository.findByTaskId(a)).isEmpty();
    }

    @Test
    void delete_onlyRemovesTheMatchingEdge() {
        repository.save(new TaskDependency(a, b));
        repository.save(new TaskDependency(a, c));

        repository.delete(a, b);

        assertThat(repository.findByTaskId(a))
                .extracting(TaskDependency::dependsOnTaskId)
                .containsExactly(c);
    }

    // --- dual-map consistency invariant ---------------------------------

    @Test
    void save_thenDelete_keepsBothAdjacencyMapsConsistent() {
        repository.save(new TaskDependency(a, b));

        assertThat(repository.findByTaskId(a))
                .extracting(TaskDependency::dependsOnTaskId)
                .containsExactly(b);
        assertThat(repository.findByDependsOnTaskId(b))
                .extracting(TaskDependency::taskId)
                .containsExactly(a);

        repository.delete(a, b);

        assertThat(repository.findByTaskId(a)).isEmpty();
        assertThat(repository.findByDependsOnTaskId(b)).isEmpty();
    }

    // --- concurrency -----------------------------------------------------

    @Test
    void save_underConcurrentDuplicateAttempts_succeedsExactlyOnce() throws InterruptedException {
        int threadCount = 20;
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger noOps = new AtomicInteger();

        ConcurrentTestRunner.run(threadCount, () -> {
            Optional<TaskDependency> result = repository.save(new TaskDependency(a, b));
            if (result.isPresent()) {
                successes.incrementAndGet();
            } else {
                noOps.incrementAndGet();
            }
        });

        assertThat(successes.get()).isEqualTo(1);
        assertThat(noOps.get()).isEqualTo(threadCount - 1);
        assertThat(repository.findByTaskId(a))
                .extracting(TaskDependency::dependsOnTaskId)
                .containsExactly(b);
    }

    @Test
    void delete_underConcurrentDuplicateAttempts_succeedsExactlyOnce() throws InterruptedException {
        repository.save(new TaskDependency(a, b));
        int threadCount = 20;
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger noOps = new AtomicInteger();

        ConcurrentTestRunner.run(threadCount, () -> {
            boolean removed = repository.delete(a, b);
            if (removed) {
                successes.incrementAndGet();
            } else {
                noOps.incrementAndGet();
            }
        });

        assertThat(successes.get()).isEqualTo(1);
        assertThat(noOps.get()).isEqualTo(threadCount - 1);
        assertThat(repository.findByTaskId(a)).isEmpty();
    }

    @Test
    void save_concurrentDistinctEdgesWithSameTaskId_hasNoLostUpdates() throws InterruptedException {
        int edgeCount = 50;
        UUID[] dependsOnIds = new UUID[edgeCount];
        for (int i = 0; i < edgeCount; i++) {
            dependsOnIds[i] = UUID.randomUUID();
        }

        ConcurrentTestRunner.run(edgeCount, index -> repository.save(new TaskDependency(a, dependsOnIds[index])));

        assertThat(repository.findByTaskId(a))
                .extracting(TaskDependency::dependsOnTaskId)
                .containsExactlyInAnyOrder(dependsOnIds);
    }
}
