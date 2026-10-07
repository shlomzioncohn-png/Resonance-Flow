package com.rnd.chain.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.chain.repository.ConstraintRepository;
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
 * placed directly on a candidate class, this reliably self-registers, the same pattern
 * {@link MockDataConfig}'s {@code TaskRepository}/{@code TaskDependencyRepository} beans use (see
 * its Javadoc). Unlike those, there is no in-memory {@code ConstraintRepository} implementation
 * to fall back to - Constraint had no in-memory repository to begin with (issue #378's own
 * description) - so this is currently the only bean that can satisfy this type.
 */
@Configuration
public class ConstraintRepositoryConfig {

    @Bean
    @ConditionalOnMissingBean(ConstraintRepository.class)
    public ConstraintRepository constraintRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new PostgresConstraintRepository(jdbcTemplate, objectMapper);
    }
}
