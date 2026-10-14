package com.rnd.chain.config;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.repository.DependencyHistoryRepository;
import com.rnd.chain.repository.GraphLock;
import com.rnd.chain.repository.InMemoryDependencyHistoryRepository;
import com.rnd.chain.repository.InMemoryGraphLock;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires the in-memory mock repositories as Spring beans, so services
 * such as {@code TaskTraversalService} and {@code TaskDependencyService}
 * can be constructed via normal dependency injection when no real
 * {@code DataSource} is wired for this module yet (see {@code CLAUDE.md}'s
 * "Open items" for current status).
 *
 * <p>Each bean is guarded with {@link ConditionalOnMissingBean} so a
 * consumer that registers its own {@link TaskRepository}/
 * {@link TaskDependencyRepository} (e.g. a real DB-backed implementation,
 * or a test double) is not overridden by this mock.
 *
 * <p>General-purpose infrastructure beans (not mock data) live in
 * {@link InfrastructureConfig} instead.
 */
@Configuration
public class MockDataConfig {

    @Bean
    @ConditionalOnMissingBean(TaskRepository.class)
    public TaskRepository taskRepository() {
        List<Task> noSeedTasks = List.of();
        return new InMemoryTaskRepository(noSeedTasks);
    }

    @Bean
    @ConditionalOnMissingBean(TaskDependencyRepository.class)
    public TaskDependencyRepository taskDependencyRepository() {
        List<TaskDependency> noSeedEdges = List.of();
        return new InMemoryTaskDependencyRepository(noSeedEdges);
    }

    @Bean
    @ConditionalOnMissingBean(DependencyHistoryRepository.class)
    public DependencyHistoryRepository dependencyHistoryRepository() {
        return new InMemoryDependencyHistoryRepository();
    }

    @Bean
    @ConditionalOnMissingBean(GraphLock.class)
    public GraphLock graphLock() {
        return new InMemoryGraphLock();
    }
}
