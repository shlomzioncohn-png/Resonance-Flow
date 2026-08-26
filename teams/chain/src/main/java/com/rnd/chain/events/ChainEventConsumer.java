package com.rnd.chain.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.chain.dto.ProcessedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Listens to configured Kafka topics, parses the event envelope,
 * and routes each event to the matching EventHandler based on eventType.
 *
 * This bean is only created when chain.kafka.consumed-topics is set in
 * application.yml. Until the first approved cross-team contract exists
 * and a real topic is configured, this component stays disabled entirely -
 * an empty topic list would otherwise fail application startup.
 */
@Component
@ConditionalOnProperty(prefix = "chain.kafka", name = "consumed-topics")
public class ChainEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChainEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final Map<String, EventHandler<?>> handlersByEventType;
    private final ProcessedEventStore processedEventStore;

    public ChainEventConsumer(ObjectMapper objectMapper, List<EventHandler<?>> handlers,
                              ProcessedEventStore processedEventStore) {
        this.objectMapper = objectMapper;
        this.handlersByEventType = handlers.stream()
                .collect(Collectors.toMap(EventHandler::getSupportedEventType, h -> h));
        this.processedEventStore = processedEventStore;
    }

    @KafkaListener(topics = "${chain.kafka.consumed-topics:}", groupId = "chain-service")
    public void onMessage(String rawMessage) throws Exception {
        JsonNode root = objectMapper.readTree(rawMessage);
        String eventType = root.get("eventType").asText();

        EventHandler<?> handler = handlersByEventType.get(eventType);
        if (handler == null) {
            // Not a processing failure - no retry/DLT would help here, there is
            // simply no handler registered yet for this event type.
            log.warn("No handler registered for event type [{}] - message ignored", eventType);
            return;
        }

        // Any exception from here on propagates out of onMessage on purpose,
        // so ChainKafkaErrorHandlingConfig's DefaultErrorHandler can retry it
        // and eventually route it to the DLT if it keeps failing.
        dispatch(handler, root);
    }

    private <T> void dispatch(EventHandler<T> handler, JsonNode root) throws Exception {
        UUID eventId = UUID.fromString(root.get("eventId").asText());
        String eventType = root.get("eventType").asText();

        if (processedEventStore.isProcessed(eventId)) {
            // Already handled before (e.g. a duplicate delivery from Kafka) -
            // skip calling the handler again to avoid duplicate state changes.
            log.info("Event [{}] of type [{}] was already processed - skipping", eventId, eventType);
            return;
        }

        Instant occurredAt = Instant.parse(root.get("occurredAt").asText());
        String correlationId = root.hasNonNull("correlationId") ? root.get("correlationId").asText() : null;
        T payload = objectMapper.treeToValue(root.get("payload"), handler.getPayloadType());

        EventEnvelope<T> event = new EventEnvelope<>(eventId, eventType, occurredAt, correlationId, payload);
        handler.handle(event);

        // Only mark as processed after handle() succeeds - if it throws,
        // the event stays unmarked so a retry will still call the handler.
        processedEventStore.markProcessed(new ProcessedEvent(eventId, eventType, Instant.now()));
    }
}