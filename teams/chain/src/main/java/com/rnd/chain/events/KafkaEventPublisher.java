package com.rnd.chain.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * EventPublisher implementation that actually publishes to Kafka.
 * Responsible for: serialization, topic resolution by event type, logging, and error handling.
 *
 * publish() is intentionally fire-and-forget from the caller's point of
 * view - a Kafka send failure is only logged, never surfaced back to
 * whatever business logic already committed its own state change before
 * calling publish(). That is still a real dual-write risk (state committed,
 * publish fails, no compensating action) for any business service that
 * calls publish() directly. The mitigation is ChainOutboxWorker, which uses
 * this class's package-private publishAndWait() instead, inside the same
 * DB transaction as the business write - see its Javadoc, and PLAN.md
 * decision D6 for why that only became possible once Chain had a database
 * to put an outbox table in.
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
        this.eventTypeToTopic = topicsProperties.topics() != null
                ? Map.copyOf(topicsProperties.topics())
                : Map.of();
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

    /**
     * Publishes and blocks until Kafka has acknowledged the send (or thrown),
     * so the caller knows synchronously whether it succeeded. Deliberately
     * not part of the EventPublisher interface: regular business logic
     * should stay fire-and-forget per publish()'s own contract. Package-
     * private and used only by ChainOutboxWorker, which must know the
     * outcome before its surrounding @Transactional method commits the row's
     * new status.
     */
    void publishAndWait(EventEnvelope<?> event) {
        String topic = resolveTopic(event.getEventType());
        String serialized = serialize(event);

        try {
            kafkaTemplate.send(topic, event.getEventId().toString(), serialized).get();
            log.info("Published event [{}] of type [{}] to topic [{}]",
                    event.getEventId(), event.getEventType(), topic);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException(
                    "Interrupted while publishing event " + event.getEventId() + " to topic " + topic, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new EventPublishException(
                    "Failed to publish event " + event.getEventId() + " to topic " + topic, cause);
        }
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
