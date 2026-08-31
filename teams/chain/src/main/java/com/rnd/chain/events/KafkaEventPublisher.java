package com.rnd.chain.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * EventPublisher implementation that actually publishes to Kafka.
 * Responsible for: serialization, topic resolution by event type, logging, and error handling.
 *
 * TODO: publish() is fire-and-forget from the caller's point of view - a
 * Kafka send failure is only logged (see below), never surfaced back to
 * whatever business logic already committed its own state change before
 * calling publish(). This is a real dual-write risk (state committed,
 * publish fails, no compensating action) with no outbox or equivalent
 * mitigation today. An Outbox pattern already exists elsewhere in this
 * repository (Inbox's OutboxEvent/OutboxWorker), but it is Inbox-specific
 * application code - its own JPA entity, its own event-type enum, its own
 * Postgres database - with no shared module Chain could depend on, and
 * Chain has no database of its own yet. Revisit once Chain adopts
 * persistence; see PLAN.md decision D6 for the full reasoning on reuse vs.
 * reimplementation vs. extracting a shared module.
 */
@Component
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, String> eventTypeToTopic;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper,
                               ChainEventTopicsProperties topicsProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.eventTypeToTopic = topicsProperties.topics();
    }

    @Override
    public void publish(EventEnvelope<?> event) {
        String topic = resolveTopic(event.getEventType());
        String serialized = serialize(event);

        kafkaTemplate.send(topic, event.getEventId().toString(), serialized)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish event [{}] of type [{}] to topic [{}]",
                                event.getEventId(), event.getEventType(), topic, ex);
                    } else {
                        log.info("Published event [{}] of type [{}] to topic [{}]",
                                event.getEventId(), event.getEventType(), topic);
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

    private String serialize(EventEnvelope<?> event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new EventPublishException(
                    "Failed to serialize event of type " + event.getEventType(), e);
        }
    }
}
