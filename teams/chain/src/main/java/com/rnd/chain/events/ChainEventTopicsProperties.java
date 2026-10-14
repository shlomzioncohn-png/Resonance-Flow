package com.rnd.chain.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Binds Chain's chain.kafka.* topic/group configuration:
 * - topics: event type -> Kafka topic. Used by {@code events.KafkaSchedulingUpdatePublisher}
 *   (#564) to resolve where to publish task.scheduling.updated.
 * - consumerGroupId: the consumer group id every one of Chain's
 *   per-topic @KafkaListener methods joins (TaskCreatedEventHandler,
 *   TaskUpdatedEventHandler, DependencyProposedEventHandler) - one shared
 *   group for the whole service, matching Inbox's "inbox-service"
 *   convention, set explicitly rather than left to Spring's
 *   ${spring.application.name} default ("chain").
 *
 * consumerGroupId is also referenced directly via a property placeholder
 * on each listener's @KafkaListener annotation (Spring Kafka listener
 * groupId must be resolvable at annotation-processing time, so it can't
 * be read off this bean directly at runtime) - it's bound here too so
 * this class is a complete, accurate picture of Chain's Kafka topic/group
 * configuration for anyone reading it, not just the publish side.
 *
 * consumedTopics (the old single comma-joined topic list ChainEventConsumer's
 * generic multi-topic listener used) is gone along with that class - each
 * handler now names its own topic directly on its own @KafkaListener.
 *
 * A record with no Java-side default for consumerGroupId on purpose
 * (PLAN.md Finding 3): application.yml always sets
 * chain.kafka.consumer-group-id explicitly, so a second, different-valued
 * Java default here would be dead code that could silently disagree with
 * the real value. If the property is ever genuinely missing, binding
 * simply leaves consumerGroupId null (and each listener's @KafkaListener
 * placeholder, which also has no fallback, fails loudly at startup)
 * rather than falling back to a third, undocumented value.
 *
 * Registered via @EnableConfigurationProperties on ChainApplication, not
 * @Component: a record @ConfigurationProperties bean needs Spring Boot's
 * constructor/value-object binding, which only activates for beans
 * registered through @EnableConfigurationProperties (or
 * @ConfigurationPropertiesScan) - a plain @Component-scanned record is
 * instead treated as an ordinary bean and Spring tries to autowire its
 * canonical constructor's parameters (Map, String) as beans from the
 * context, which fails at startup ("No qualifying bean of type
 * 'java.lang.String' available").
 */
@ConfigurationProperties(prefix = "chain.kafka")
public record ChainEventTopicsProperties(
        Map<String, String> topics,
        String consumerGroupId) {
}
