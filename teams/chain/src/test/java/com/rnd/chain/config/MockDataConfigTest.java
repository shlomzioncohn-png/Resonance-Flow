package com.rnd.chain.config;

import com.rnd.chain.repository.DependencyHistoryRepository;
import com.rnd.chain.repository.InMemoryDependencyHistoryRepository;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockDataConfigTest {

    @Test
    void taskRepository_doesNotOverrideAnExistingBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(CustomTaskRepositoryConfig.class, MockDataConfig.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(TaskRepository.class)).hasSize(1);
                    assertThat(context.getBean(TaskRepository.class))
                            .isSameAs(CustomTaskRepositoryConfig.CUSTOM_INSTANCE);
                });
    }

    @Test
    void taskDependencyRepository_doesNotOverrideAnExistingBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(CustomTaskDependencyRepositoryConfig.class, MockDataConfig.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(TaskDependencyRepository.class)).hasSize(1);
                    assertThat(context.getBean(TaskDependencyRepository.class))
                            .isSameAs(CustomTaskDependencyRepositoryConfig.CUSTOM_INSTANCE);
                });
    }

    @Test
    void taskRepository_isCreated_whenNoOtherBeanIsRegistered() {
        new ApplicationContextRunner()
                .withUserConfiguration(MockDataConfig.class)
                .run(context -> assertThat(context.getBeansOfType(TaskRepository.class)).hasSize(1));
    }

    @Test
    void taskDependencyRepository_isCreated_whenNoOtherBeanIsRegistered() {
        new ApplicationContextRunner()
                .withUserConfiguration(MockDataConfig.class)
                .run(context -> assertThat(context.getBeansOfType(TaskDependencyRepository.class)).hasSize(1));
    }

    @Test
    void dependencyHistoryRepository_doesNotOverrideAnExistingBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(CustomDependencyHistoryRepositoryConfig.class, MockDataConfig.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(DependencyHistoryRepository.class)).hasSize(1);
                    assertThat(context.getBean(DependencyHistoryRepository.class))
                            .isSameAs(CustomDependencyHistoryRepositoryConfig.CUSTOM_INSTANCE);
                });
    }

    @Test
    void dependencyHistoryRepository_isCreated_whenNoOtherBeanIsRegistered() {
        new ApplicationContextRunner()
                .withUserConfiguration(MockDataConfig.class)
                .run(context -> assertThat(context.getBeansOfType(DependencyHistoryRepository.class)).hasSize(1));
    }

    @Configuration
    static class CustomTaskRepositoryConfig {
        static final TaskRepository CUSTOM_INSTANCE = new InMemoryTaskRepository(List.of());

        @Bean
        TaskRepository taskRepository() {
            return CUSTOM_INSTANCE;
        }
    }

    @Configuration
    static class CustomTaskDependencyRepositoryConfig {
        static final TaskDependencyRepository CUSTOM_INSTANCE = new InMemoryTaskDependencyRepository(List.of());

        @Bean
        TaskDependencyRepository taskDependencyRepository() {
            return CUSTOM_INSTANCE;
        }
    }

    @Configuration
    static class CustomDependencyHistoryRepositoryConfig {
        static final DependencyHistoryRepository CUSTOM_INSTANCE = new InMemoryDependencyHistoryRepository();

        @Bean
        DependencyHistoryRepository dependencyHistoryRepository() {
            return CUSTOM_INSTANCE;
        }
    }

}
