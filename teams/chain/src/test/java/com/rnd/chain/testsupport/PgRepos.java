package com.rnd.chain.testsupport;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.events.ProcessedEventStore;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.DependencyHistoryRepository;
import com.rnd.chain.repository.GraphLock;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.repository.TransactionRunner;
import com.rnd.chain.repository.UnresolvedDependencyRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Gives Postgres-backed tests the real repositories from the Spring context, with a seeding
 * shortcut. Only meaningful inside a {@code @SpringBootTest}; the context's beans are exposed
 * statically so ported test fixtures can stay plain methods.
 */
@Component
public class PgRepos {

    private static TaskRepository taskRepository;
    private static TaskDependencyRepository dependencyRepository;
    private static ConstraintRepository constraintRepository;
    private static DependencyHistoryRepository historyRepository;
    private static UnresolvedDependencyRepository unresolvedRepository;
    private static GraphLock graphLock;
    private static TransactionRunner transactionRunner;
    private static ProcessedEventStore processedEventStore;
    private static EntityManager entityManager;

    public PgRepos(TaskRepository tasks, TaskDependencyRepository dependencies, ConstraintRepository constraints,
                   DependencyHistoryRepository history, UnresolvedDependencyRepository unresolved,
                   GraphLock lock, TransactionRunner runner, ProcessedEventStore processedEvents,
                   EntityManager em) {
        taskRepository = tasks;
        dependencyRepository = dependencies;
        constraintRepository = constraints;
        historyRepository = history;
        unresolvedRepository = unresolved;
        graphLock = lock;
        transactionRunner = runner;
        processedEventStore = processedEvents;
        entityManager = em;
    }

    /**
     * Saves {@code seed} and returns the real repository. Rows are inserted without parents first and
     * linked afterwards, so ordering (and parent cycles) in the seed does not matter.
     */
    public static TaskRepository tasks(List<Task> seed) {
        List<Task> normalized = seed;
        normalized.forEach(t -> taskRepository.save(rebuild(t, t.getTenantId(), null)));
        normalized.stream().filter(t -> t.getParentTaskId() != null).forEach(t -> taskRepository.save(rebuild(t, t.getTenantId(), t.getParentTaskId())));
        return taskRepository;
    }

    public static TaskDependencyRepository dependencies(List<TaskDependency> seed) {
        seed.forEach(dependencyRepository::save);
        return dependencyRepository;
    }

    public static ConstraintRepository constraints() {
        return constraintRepository;
    }

    public static DependencyHistoryRepository history() {
        return historyRepository;
    }

    public static UnresolvedDependencyRepository unresolved() {
        return unresolvedRepository;
    }

    public static GraphLock graphLock() {
        return graphLock;
    }

    public static TransactionRunner transactionRunner() {
        return transactionRunner;
    }

    public static ProcessedEventStore processedEvents() {
        return processedEventStore;
    }

    /** Flushes pending writes and drops the JPA first-level cache (needed after native upserts). */
    public static void clearPersistenceContext() {
        entityManager.flush();
        entityManager.clear();
    }

    private static Task rebuild(Task t, UUID tenant, UUID parent) {
        return Task.builder().id(t.getId()).parentTaskId(parent).tenantId(tenant).status(t.getStatus())
                .isReady(t.isReady()).blockingCount(t.getBlockingCount())
                .openConstraintCount(t.getOpenConstraintCount())
                .estimatedDurationMin(t.getEstimatedDurationMin()).score(t.getScore())
                .confidence(t.getConfidence()).priorityScore(t.getPriorityScore())
                .createdAt(t.getCreatedAt() != null ? t.getCreatedAt() : java.time.LocalDateTime.now())
                .updatedAt(t.getUpdatedAt() != null ? t.getUpdatedAt() : java.time.LocalDateTime.now()).deletedAt(t.getDeletedAt()).build();
    }
}
