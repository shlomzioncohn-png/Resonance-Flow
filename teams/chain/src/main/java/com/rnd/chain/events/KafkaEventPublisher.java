package com.rnd.chain.events;

import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * EventPublisher implementation that actually publishes to Kafka.
 *
 * Publishes the Protobuf {@code payload} as-is (no envelope) - see EventPublisher's Javadoc for why.
 * The Kafka message key is whatever the caller passes in (e.g. taskId for task.scheduling.updated,
 * matching Inbox's own consumer's partition-ordering assumption), never a locally generated event
 * id.
 *
 * publish() is intentionally fire-and-forget from the caller's point of
 * view - a Kafka send failure is only logged, never surfaced back to
 * whatever business logic already committed its own state change before
 * calling publish(). That is still a real dual-write risk (state committed,
 * publish fails, no compensating action) for any business service that
 * calls publish() directly. Guarding against that (e.g. a transactional
 * outbox) is out of scope for this infrastructure layer.
 */
@Component
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, Message> kafkaTemplate;
    private final Map<String, String> eventTypeToTopic;

    public KafkaEventPublisher(KafkaTemplate<String, Message> kafkaTemplate,
                               ChainEventTopicsProperties topicsProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.eventTypeToTopic = topicsProperties.topics() != null
                ? Map.copyOf(topicsProperties.topics())
                : Map.of();
    }

    @Override
    public void publish(String eventType, String key, Message payload) {
        Objects.requireNonNull(payload, "payload");
        String topic = resolveTopic(eventType);

        kafkaTemplate.send(topic, key, payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish [{}] (key={}) to topic [{}]", eventType, key, topic, ex);
                    } else {
                        log.info("Published [{}] (key={}) to topic [{}]", eventType, key, topic);
                    }
                });
    }

    private String resolveTopic(String eventType) {
        String topic = eventTypeToTopic.get(eventType);
        if (topic == null) {
            throw new EventPublishException("No topic configured for event type: " + eventType);
        }
        return topic;
    }
}
