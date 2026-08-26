package com.rnd.chain.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;


/**
 * Binds the chain.kafka.topics map from application.yml - maps an event
 * type to the Kafka topic it should be published to.
 */
@Component
@ConfigurationProperties(prefix = "chain.kafka")
public class ChainEventTopicsProperties {

    private Map<String, String> topics = new HashMap<>();

    public Map<String, String> getTopics() {
        return topics;
    }

    public void setTopics(Map<String, String> topics) {
        this.topics = topics;
    }
}