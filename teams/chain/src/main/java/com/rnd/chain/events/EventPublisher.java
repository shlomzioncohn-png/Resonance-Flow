package com.rnd.chain.events;

/**
 * Publishes events produced by Chain (e.g. to Kafka).
 *
 * No envelope: the retired EventEnvelope wrapper assumed a Chain-invented cross-team format that
 * was never actually agreed with anyone (see its own former Javadoc). Every consumer Chain
 * publishes to expects its own, team-owned flat contract instead (e.g. Inbox's SchedulingEventDto
 * for task.scheduling.updated) - so publish() takes an already-shaped payload and serializes it
 * as-is.
 */
public interface EventPublisher {

    /**
     * @param eventType a config key used to resolve which Kafka topic to publish to
     *                  (chain.kafka.topics.&lt;eventType&gt;) - not serialized as part of the
     *                  wire payload.
     * @param key       the Kafka message key - callers should key by the entity the event is
     *                  about (e.g. taskId), not a per-event id, so every event about the same
     *                  entity lands in order on one partition.
     * @param payload   the flat payload to serialize and publish as-is.
     */
    void publish(String eventType, String key, Object payload);
}
