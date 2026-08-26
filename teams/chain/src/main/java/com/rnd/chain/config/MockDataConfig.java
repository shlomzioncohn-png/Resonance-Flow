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
 * Wires the in-memory mock repositories as Spring beans, so services
 * such as {@code TaskDependencyService} can be constructed via normal
 * dependency injection - no real DB connection (per current
 * team-lead instruction; no DB technology has been chosen yet).
 */
@Configuration
public class MockDataConfig {

    @Bean
    public TaskRepository taskRepository() {
        List<Task> fakeTasks = List.of(); // TODO: populate with sample data
        return new InMemoryTaskRepository(fakeTasks);
    }

    @Bean
    public TaskDependencyRepository taskDependencyRepository() {
        List<TaskDependency> fakeEdges = List.of(); // TODO: populate with sample data
        return new InMemoryTaskDependencyRepository(fakeEdges);
    }
}
