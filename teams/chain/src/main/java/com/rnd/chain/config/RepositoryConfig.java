package com.rnd.chain.config;

import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires the in-memory repository implementations as beans, seeded empty. There is no real
 * persistence layer yet - see CLAUDE.md "Open items". Replace with real JPA-backed beans once
 * the team lead's DB decision lands. Required for TaskEligibilityService's @Service to actually
 * resolve - without a TaskRepository/TaskDependencyRepository bean, Spring can't construct it.
 */
@Configuration
public class RepositoryConfig {

    /**
     * Singleton (default Spring bean scope) so every consumer shares the same in-memory store -
     * required for TaskEligibilityService's locking to actually be effective, since locking
     * only closes races if all callers see the same repository instance. Seeded empty because
     * there is no real persistence layer yet to seed from (see CLAUDE.md "Open items") - all
     * Task data is created at runtime through the service layer, not preloaded (review finding
     * #12).
     */
    @Bean
    public TaskRepository taskRepository() {
        return new InMemoryTaskRepository(List.of());
    }

    /**
     * Singleton, empty-seeded for the same reasons as {@link #taskRepository()}: a shared
     * instance is required for the dependency-graph locking in TaskEligibilityService to be
     * effective, and there is no real persistence layer yet to seed edges from (review finding
     * #12).
     */
    @Bean
    public TaskDependencyRepository taskDependencyRepository() {
        return new InMemoryTaskDependencyRepository(List.of());
    }
}
