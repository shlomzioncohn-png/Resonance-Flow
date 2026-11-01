package com.rnd.chain.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.chain.repository.FlowHealthFlagRepository;
import com.rnd.chain.repository.PostgresFlowHealthFlagRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the Postgres-backed {@link FlowHealthFlagRepository}; same pattern as {@link ConstraintRepositoryConfig}. */
@Configuration
public class FlowHealthRepositoryConfig {

    @Bean
    @ConditionalOnMissingBean(FlowHealthFlagRepository.class)
    public FlowHealthFlagRepository flowHealthFlagRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new PostgresFlowHealthFlagRepository(jdbcTemplate, objectMapper);
    }
}
