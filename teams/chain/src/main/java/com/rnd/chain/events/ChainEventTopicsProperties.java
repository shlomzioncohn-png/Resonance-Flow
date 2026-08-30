package com.rnd.chain.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
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
 */
@Component
@ConfigurationProperties(prefix = "chain.kafka")
public class ChainEventTopicsProperties {

    private Map<String, String> topics = new HashMap<>();
    private String consumedTopics = "";
    private String consumerGroupId = "chain-service";

    public Map<String, String> getTopics() {
        return topics;
    }

    public void setTopics(Map<String, String> topics) {
        this.topics = topics;
    }

    public String getConsumedTopics() {
        return consumedTopics;
    }

    public void setConsumedTopics(String consumedTopics) {
        this.consumedTopics = consumedTopics;
    }

    public String getConsumerGroupId() {
        return consumerGroupId;
    }

    public void setConsumerGroupId(String consumerGroupId) {
        this.consumerGroupId = consumerGroupId;
    }
}
