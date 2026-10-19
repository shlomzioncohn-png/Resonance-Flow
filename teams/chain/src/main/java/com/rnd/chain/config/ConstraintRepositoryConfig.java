package com.rnd.chain.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.chain.repository.ConstraintHistoryRepository;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.PostgresConstraintHistoryRepository;
import com.rnd.chain.repository.PostgresConstraintRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the real, Postgres-backed {@link ConstraintRepository} (issue #378) as a Spring bean.
 *
 * <p>{@code @ConditionalOnMissingBean} is on this {@code @Bean} factory method, not on
 * {@link PostgresConstraintRepository} itself - unlike {@code @Component}/{@code @Repository}
 * placed directly on a candidate class, this reliably self-registers.
 */
@Configuration
public class ConstraintRepositoryConfig {

    @Bean
    @ConditionalOnMissingBean(ConstraintRepository.class)
    public ConstraintRepository constraintRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new PostgresConstraintRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(ConstraintHistoryRepository.class)
    public ConstraintHistoryRepository constraintHistoryRepository(JdbcTemplate jdbcTemplate) {
        return new PostgresConstraintHistoryRepository(jdbcTemplate);
    }
}
