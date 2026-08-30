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

    @Bean
    public TaskRepository taskRepository() {
        return new InMemoryTaskRepository(List.of());
    }

    @Bean
    public TaskDependencyRepository taskDependencyRepository() {
        return new InMemoryTaskDependencyRepository(List.of());
    }
}
