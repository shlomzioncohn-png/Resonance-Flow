package com.rnd.chain.config;

import com.rnd.chain.repository.InMemoryTransactionRunner;
import com.rnd.chain.repository.TransactionRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Default beans for general-purpose infrastructure that every service needs
 * regardless of which {@code TaskRepository}/{@code TaskDependencyRepository}
 * is wired - as opposed to {@link MockDataConfig}, which seeds the in-memory
 * repositories themselves. Split out separately so a reader looking for where
 * the application's {@link Clock} or {@link TransactionRunner} comes from
 * doesn't have to look inside a class named "mock data".
 *
 * <p>Each bean is guarded with {@link ConditionalOnMissingBean} so a
 * consumer that registers its own (e.g. a JPA-backed {@code TransactionRunner})
 * is not overridden by this default.
 */
@Configuration
public class InfrastructureConfig {

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
