package com.rnd.chain.events;

/**
 * Publishes events produced by Chain (e.g. to Kafka).
 */
public interface EventPublisher {

    void publish(EventEnvelope<?> event);
}

