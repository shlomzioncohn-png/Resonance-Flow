package com.rnd.chain.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds chain.outbox.* - see ChainOutboxWorker and ChainOutboxCleanupJob for
 * how each field is used. Only takes effect once an environment has cleared
 * spring.autoconfigure.exclude and configured a real spring.datasource -
 * see application.yml's comment on that property.
 *
 * Registered via @EnableConfigurationProperties on ChainApplication - see
 * ChainEventTopicsProperties's Javadoc for why a @Component-scanned record
 * @ConfigurationProperties bean fails to start.
 */
@ConfigurationProperties(prefix = "chain.outbox")
public record ChainOutboxProperties(
        @NestedConfigurationProperty Worker worker,
        @NestedConfigurationProperty Cleanup cleanup) {

    public record Worker(
            @DefaultValue("20") int batchSize,
            @DefaultValue("5000") long pollIntervalMs,
            @DefaultValue("3") int maxRetries,
            @DefaultValue("10") long backoffBaseSeconds,
            @DefaultValue("120") long backoffMaxSeconds) {
    }

    public record Cleanup(
            @DefaultValue("3600000") long intervalMs,
            @DefaultValue("7") long retentionDays) {
    }
}
