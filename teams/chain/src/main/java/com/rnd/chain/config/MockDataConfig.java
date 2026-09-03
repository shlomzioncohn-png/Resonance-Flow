package com.rnd.chain.config;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires the in-memory repositories as Spring beans, matching the pattern of Riki-Kaplan's
 * MockDataConfig, so TaskTraversalService (and ChainController) can be constructed via normal
 * dependency injection - no real DB connection. Trimmed to just the two beans this task's
 * traversal service actually needs (no ConstraintRepository, out of scope here). No sample
 * data is seeded (empty lists): this task's own tests build their own seed data directly
 * against TaskTraversalService, not through this Spring context - these beans exist only so
 * the application context has something to inject.
 */
@Configuration
public class MockDataConfig {

    @Bean
    public TaskRepository taskRepository() {
        List<Task> tasks = List.of();
        return new InMemoryTaskRepository(tasks);
    }

    @Bean
    public TaskDependencyRepository taskDependencyRepository() {
        List<TaskDependency> edges = List.of();
        return new InMemoryTaskDependencyRepository(edges);
    }
}
