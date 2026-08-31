package com.rnd.chain.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Binds all of Chain's chain.kafka.* topic/group configuration in one
 * place:
 * - topics: event type -> Kafka topic, used by KafkaEventPublisher to
 *   resolve where to publish.
 * - consumedTopics: the topic(s) ChainEventConsumer listens to.
 * - consumerGroupId: the consumer group id ChainEventConsumer joins.
 *
 * consumedTopics and consumerGroupId are also referenced directly via
 * property placeholders on ChainEventConsumer's @KafkaListener annotation
 * (Spring Kafka listener topics/groupId must be resolvable at
 * annotation-processing time, so they can't be read off this bean
 * directly at runtime) - they are bound here too so this class is a
 * complete, accurate picture of Chain's Kafka topic/group configuration
 * for anyone reading it, not just the publish side as before.
 *
 * A record with no Java-side defaults on purpose (PLAN.md Finding 3):
 * application.yml always sets consumerGroupId
 * (${spring.application.name}) and ships in every build's classpath, so
 * a second, different-valued Java default here was dead code that
 * silently disagreed with the real value. If chain.kafka.consumer-group-id
 * is ever genuinely missing, binding simply leaves consumerGroupId null
 * (and ChainEventConsumer's @KafkaListener placeholder, which also has no
 * fallback now, fails loudly at startup) rather than falling back to a
 * third, undocumented value.
 *
 * Registered via @EnableConfigurationProperties on ChainApplication, not
 * @Component: a record @ConfigurationProperties bean needs Spring Boot's
 * constructor/value-object binding, which only activates for beans
 * registered through @EnableConfigurationProperties (or
 * @ConfigurationPropertiesScan) - a plain @Component-scanned record is
 * instead treated as an ordinary bean and Spring tries to autowire its
 * canonical constructor's parameters (Map, String, String) as beans from
 * the context, which fails at startup ("No qualifying bean of type
 * 'java.lang.String' available").
 */
@ConfigurationProperties(prefix = "chain.kafka")
public record ChainEventTopicsProperties(
        Map<String, String> topics,
        String consumedTopics,
        String consumerGroupId) {
}
