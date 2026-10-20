package com.rnd.chain.config;

import com.rnd.chain.repository.PostgresTaskReadinessRepository;
import com.rnd.chain.repository.TaskReadinessRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class TaskReadinessRepositoryConfig {

    @Bean
    @ConditionalOnMissingBean(TaskReadinessRepository.class)
    public TaskReadinessRepository taskReadinessRepository(JdbcTemplate jdbcTemplate) {
        return new PostgresTaskReadinessRepository(jdbcTemplate);
    }
}