package com.rnd.chain.repository;

import com.rnd.chain.dto.TaskDependency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

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
        repository.save(new TaskDependency(a, b));

        assertThat(repository.findByTaskId(a))
                .extracting(TaskDependency::getDependsOnTaskId)
                .containsExactly(b);
        assertThat(repository.findByDependsOnTaskId(b))
                .extracting(TaskDependency::getTaskId)
                .containsExactly(a);
    }

    @Test
    void save_isIdempotent_forTheSameEdgeSavedTwice() {
        repository.save(new TaskDependency(a, b));
        repository.save(new TaskDependency(a, b));

        assertThat(repository.findByTaskId(a)).hasSize(1);
    }

    @Test
    void save_supportsMultipleDependenciesForSameTask() {
        repository.save(new TaskDependency(a, b));
        repository.save(new TaskDependency(a, c));

        assertThat(repository.findByTaskId(a))
                .extracting(TaskDependency::getDependsOnTaskId)
                .containsExactlyInAnyOrder(b, c);
    }

    @Test
    void delete_removesEdge() {
        repository.save(new TaskDependency(a, b));

        repository.delete(a, b);

        assertThat(repository.findByTaskId(a)).isEmpty();
        assertThat(repository.findByDependsOnTaskId(b)).isEmpty();
    }

    @Test
    void delete_onEdgeThatDoesNotExist_isANoOp() {
        repository.delete(a, b);

        assertThat(repository.findByTaskId(a)).isEmpty();
    }

    @Test
    void delete_onlyRemovesTheMatchingEdge() {
        repository.save(new TaskDependency(a, b));
        repository.save(new TaskDependency(a, c));

        repository.delete(a, b);

        assertThat(repository.findByTaskId(a))
                .extracting(TaskDependency::getDependsOnTaskId)
                .containsExactly(c);
    }
}
