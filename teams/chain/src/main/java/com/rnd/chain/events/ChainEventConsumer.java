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
import java.time.format.DateTimeParseException;
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
                .collect(Collectors.toMap(
                        EventHandler::getSupportedEventType,
                        h -> h,
                        (first, second) -> {
                            throw new IllegalStateException(
                                    "Duplicate EventHandler for event type [" + first.getSupportedEventType()
                                            + "]: " + first.getClass().getName() + " and " + second.getClass().getName());
                        }));
        this.processedEventStore = processedEventStore;
    }

    // groupId has no fallback value here on purpose (PLAN.md Finding 3):
    // application.yml always sets chain.kafka.consumer-group-id, so a
    // literal fallback here was dead code that silently disagreed with
    // ChainEventTopicsProperties's own (also-removed) Java-side default.
    // If the property is ever genuinely missing, this now fails loudly at
    // startup instead of silently joining a third, undocumented group.
    @KafkaListener(topics = "${chain.kafka.consumed-topics:}",
                   groupId = "${chain.kafka.consumer-group-id}")
    public void onMessage(String rawMessage) {
        JsonNode root = parseRoot(rawMessage);
        String eventType = readEventType(root);

        EventHandler<?> handler = handlersByEventType.get(eventType);
        if (handler == null) {
            // Not a processing failure - no retry/DLT would help here, there is
            // simply no handler registered yet for this event type.
            log.warn("No handler registered for event type [{}] - message ignored", eventType);
            return;
        }

        // Any exception from here on propagates out of onMessage on purpose,
        // so ChainKafkaErrorHandlingConfig's DefaultErrorHandler can retry it
        // (or, for a NonRetryableEventException, skip straight to the DLT).
        dispatch(handler, root, eventType);
    }

    private <T> void dispatch(EventHandler<T> handler, JsonNode root, String eventType) {
        UUID eventId = readEventId(root);
        Instant occurredAt = readOccurredAt(root);
        String correlationId = root.hasNonNull("correlationId") ? root.get("correlationId").asText() : null;
        T payload = readPayload(root, handler);

        // Claiming only happens here, after the message is confirmed
        // well-formed - a message that fails parsing above is never claimed
        // at all, so it can still be replayed (e.g. from the DLT) once
        // whatever made it unparseable is fixed. tryClaim is atomic: exactly
        // one caller wins for a given eventId, which is what closes the race
        // where two listener threads (concurrency > 1, or a duplicate
        // delivery landing on two partitions) could otherwise both see "not
        // yet claimed" and both run the handler. See ProcessedEventStore's
        // Javadoc for exactly what a claim does and doesn't guarantee.
        if (!processedEventStore.tryClaim(new ProcessedEvent(eventId, eventType, Instant.now()))) {
            log.info("Event [{}] of type [{}] was already claimed - skipping", eventId, eventType);
            return;
        }

        EventEnvelope<T> event = new EventEnvelope<>(eventId, eventType, occurredAt, correlationId, payload);
        try {
            handler.handle(event);
        } catch (RuntimeException e) {
            // Handling failed - release the claim so a retry (whether
            // DefaultErrorHandler retrying this same delivery, or a later
            // redelivery) can claim and attempt this event again. A claim is
            // only meant to be permanent once handling actually succeeds -
            // see ProcessedEventStore's Javadoc for the one case this still
            // doesn't cover (a process crash between the claim above and
            // this catch block never releases it).
            processedEventStore.release(eventId);
            throw e;
        }
    }

    private JsonNode parseRoot(String rawMessage) {
        try {
            return objectMapper.readTree(rawMessage);
        } catch (Exception e) {
            // Logged here (not just wrapped as `cause`) so the original exception's type and
            // stack trace are visible in application logs without requiring whoever triages the
            // DLT message to inspect NonRetryableEventException#getCause() - a bug in Chain's own
            // JSON handling should be easy to tell apart from a genuinely malformed message.
            log.warn("Failed to parse message as JSON: {}", e.getMessage(), e);
            throw new NonRetryableEventException("Malformed message payload: " + e.getMessage(), e);
        }
    }

    private String readEventType(JsonNode root) {
        if (!root.hasNonNull("eventType")) {
            throw new NonRetryableEventException("Message is missing required field [eventType]");
        }
        return root.get("eventType").asText();
    }

    private UUID readEventId(JsonNode root) {
        if (!root.hasNonNull("eventId")) {
            throw new NonRetryableEventException("Message is missing required field [eventId]");
        }
        String rawEventId = root.get("eventId").asText();
        try {
            return UUID.fromString(rawEventId);
        } catch (IllegalArgumentException e) {
            throw new NonRetryableEventException("Message has an invalid [eventId] (not a UUID): " + rawEventId, e);
        }
    }

    private Instant readOccurredAt(JsonNode root) {
        if (!root.hasNonNull("occurredAt")) {
            throw new NonRetryableEventException("Message is missing required field [occurredAt]");
        }
        String rawOccurredAt = root.get("occurredAt").asText();
        try {
            return Instant.parse(rawOccurredAt);
        } catch (DateTimeParseException e) {
            throw new NonRetryableEventException(
                    "Message has an invalid [occurredAt] (not an ISO-8601 instant): " + rawOccurredAt, e);
        }
    }

    private <T> T readPayload(JsonNode root, EventHandler<T> handler) {
        try {
            return objectMapper.treeToValue(root.get("payload"), handler.getPayloadType());
        } catch (Exception e) {
            // See parseRoot's comment: logged here so a bug in our own deserialization logic
            // (as opposed to a genuinely bad producer payload) is immediately visible, not only
            // discoverable by inspecting the wrapped exception's cause.
            log.warn("Failed to deserialize payload for handler [{}]: {}", handler.getClass().getName(), e.getMessage(), e);
            throw new NonRetryableEventException(
                    "Message payload does not match the expected shape for handler ["
                            + handler.getClass().getName() + "]: " + e.getMessage(), e);
        }
    }
}
