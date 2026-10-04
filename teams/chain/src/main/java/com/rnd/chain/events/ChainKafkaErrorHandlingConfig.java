package com.rnd.chain.events;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka error handling for Chain's consumers.
 *
 * TODO: this uses Spring Kafka's default DLT naming convention
 * ("<original-topic>.DLT"). Confirm with the team whether the project
 * already has a different DLT naming/topic convention and adjust the
 * recoverer below if so. Note: this preserves the original record's
 * partition number on the DLT topic (DeadLetterPublishingRecoverer's
 * default) - if a ".DLT" topic doesn't have at least as many partitions as
 * its source topic, publishing to it can fail for higher-numbered
 * partitions. Worth checking DLT topics are provisioned with matching
 * partition counts once they're actually provisioned somewhere.
 *
 * Retry policy: chain.kafka.retry.max-attempts retries after the initial delivery attempt,
 * chain.kafka.retry.interval-ms apart (see ChainKafkaRetryProperties) - so the default
 * (max-attempts=2, interval-ms=2000) means 3 attempts total, 2 seconds apart, matching the
 * previously-hardcoded FixedBackOff(2000L, 2L). This is only for failures that might succeed
 * on retry: NonRetryableEventException (malformed messages, missing/invalid fields, a payload
 * that does not match the target handler's type - see ChainEventConsumer) skips retry
 * entirely and goes straight to the DLT, since retrying a permanently-malformed message can
 * never succeed and would only delay it reaching the DLT where it can actually be triaged.
 */
@Configuration
public class ChainKafkaErrorHandlingConfig {

    @Bean
    public DefaultErrorHandler chainKafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                       ChainKafkaRetryProperties retryProperties) {
        // No custom destination resolver: DeadLetterPublishingRecoverer's
        // default ("<topic>.DLT", same partition) is exactly what a custom
        // resolver here used to hand-roll - this constructor already does
        // that, so there is nothing left to customize.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

        FixedBackOff backOff = new FixedBackOff(retryProperties.intervalMs(), retryProperties.maxAttempts());

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(NonRetryableEventException.class);
        return errorHandler;
    }
}
