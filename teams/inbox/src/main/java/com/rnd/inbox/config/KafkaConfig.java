package com.rnd.inbox.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class KafkaConfig {

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService retryScheduler() {
        return Executors.newScheduledThreadPool(2);
    }
}
