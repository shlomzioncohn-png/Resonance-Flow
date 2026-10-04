package com.rnd.chain.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds the retry policy ChainKafkaErrorHandlingConfig applies to failed
 * message handling. Both fields are passed straight into
 * {@code new FixedBackOff(intervalMs, maxAttempts)}: maxAttempts is the number
 * of retries after the initial delivery attempt (not the total attempt count -
 * FixedBackOff's own semantics), intervalMs is the number of milliseconds
 * between attempts. Defaults (2 retries, 2000ms) mean 3 attempts total,
 * 2 seconds apart, matching the previously-hardcoded FixedBackOff(2000L, 2L)
 * exactly, so an environment that does not set chain.kafka.retry.* sees no
 * behavior change (PLAN.md Finding 5).
 *
 * Registered via @EnableConfigurationProperties on ChainApplication, not
 * @Component - see ChainEventTopicsProperties's Javadoc for why a
 * @Component-scanned record @ConfigurationProperties bean fails to start.
 */
@ConfigurationProperties(prefix = "chain.kafka.retry")
public record ChainKafkaRetryProperties(
        @DefaultValue("2") long maxAttempts,
        @DefaultValue("2000") long intervalMs) {
}
