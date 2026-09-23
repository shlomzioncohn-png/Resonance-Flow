package com.rnd.chain.config;

import com.rnd.chain.repository.InMemoryTransactionRunner;
import com.rnd.chain.repository.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class InfrastructureConfigTest {

    @Test
    void transactionRunner_doesNotOverrideAnExistingBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(CustomTransactionRunnerConfig.class, InfrastructureConfig.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(TransactionRunner.class)).hasSize(1);
                    assertThat(context.getBean(TransactionRunner.class))
                            .isSameAs(CustomTransactionRunnerConfig.CUSTOM_INSTANCE);
                });
    }

    @Test
    void transactionRunner_isCreated_whenNoOtherBeanIsRegistered() {
        new ApplicationContextRunner()
                .withUserConfiguration(InfrastructureConfig.class)
                .run(context -> assertThat(context.getBeansOfType(TransactionRunner.class)).hasSize(1));
    }

    @Test
    void clock_doesNotOverrideAnExistingBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(CustomClockConfig.class, InfrastructureConfig.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(Clock.class)).hasSize(1);
                    assertThat(context.getBean(Clock.class)).isSameAs(CustomClockConfig.CUSTOM_INSTANCE);
                });
    }

    @Test
    void clock_isCreated_whenNoOtherBeanIsRegistered() {
        new ApplicationContextRunner()
                .withUserConfiguration(InfrastructureConfig.class)
                .run(context -> assertThat(context.getBeansOfType(Clock.class)).hasSize(1));
    }

    @Configuration
    static class CustomTransactionRunnerConfig {
        static final TransactionRunner CUSTOM_INSTANCE = new InMemoryTransactionRunner();

        @Bean
        TransactionRunner transactionRunner() {
            return CUSTOM_INSTANCE;
        }
    }

    @Configuration
    static class CustomClockConfig {
        static final Clock CUSTOM_INSTANCE = Clock.systemUTC();

        @Bean
        Clock clock() {
            return CUSTOM_INSTANCE;
        }
    }
}
