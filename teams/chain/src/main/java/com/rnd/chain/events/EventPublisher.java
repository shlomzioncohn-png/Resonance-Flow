package com.rnd.chain.events;

import com.google.protobuf.Message;

/**
 * Publishes events produced by Chain (e.g. to Kafka).
 *
 * No envelope: every consumer Chain publishes to expects its own, team-owned contract (e.g.
 * Inbox's SchedulingUpdatedEvent for task.scheduling.updated), so publish() takes an
 * already-built Protobuf message and sends it as-is. Every event must carry its own event_id.
 */
public interface EventPublisher {

    /**
     * @param eventType a config key used to resolve which Kafka topic to publish to
     *                  (chain.kafka.topics.&lt;eventType&gt;) - not serialized as part of the
     *                  wire payload.
     * @param key       the Kafka message key - callers should key by the entity the event is
     *                  about (e.g. taskId), not a per-event id, so every event about the same
     *                  entity lands in order on one partition.
     * @param payload   the Protobuf message to publish as-is.
     */
    void publish(String eventType, String key, Message payload);
}
