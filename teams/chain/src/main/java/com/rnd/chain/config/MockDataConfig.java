package com.rnd.chain.config;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.repository.DependencyHistoryRepository;
import com.rnd.chain.repository.InMemoryDependencyHistoryRepository;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.repository.InMemoryTransactionRunner;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.repository.TransactionRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/**
 * Wires the in-memory mock repositories as Spring beans, so services
 * such as {@code TaskTraversalService} and {@code TaskDependencyService}
 * can be constructed via normal dependency injection.
 *
 * <p>Each bean is guarded with {@link ConditionalOnMissingBean} so a
 * consumer that registers its own {@link TaskRepository}/
 * {@link TaskDependencyRepository} (e.g. a real DB-backed implementation,
 * or a test double) is not overridden by this mock.
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
    @ConditionalOnMissingBean(TransactionRunner.class)
    public TransactionRunner transactionRunner() {
        return new InMemoryTransactionRunner();
    }

    // A @SpringBootTest overriding this must use a differently-named @Primary bean, not
    // just @Bean Clock clock() - @ConditionalOnMissingBean here runs before a nested
    // @TestConfiguration is parsed, so a same-named bean collides instead of being skipped.
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }
}
