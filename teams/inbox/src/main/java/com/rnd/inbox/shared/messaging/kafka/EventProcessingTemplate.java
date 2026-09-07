package com.rnd.inbox.shared.messaging.kafka;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.inbox.shared.exception.InboxException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.support.RetryTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

public class EventProcessingTemplate<T> {

    private static final Logger log = LoggerFactory.getLogger(EventProcessingTemplate.class);

    private final ObjectMapper objectMapper;
    private final RetryTemplate retryTemplate;
    private final ErrorTopicPublisher errorTopicPublisher;
    private final Class<T> eventType;
    private final EventValidator<T> validator;
    private final Consumer<T> persistHandler;
    private final Function<T, String> correlationIdExtractor;

    public EventProcessingTemplate(ObjectMapper objectMapper, RetryTemplate retryTemplate,
            ErrorTopicPublisher errorTopicPublisher, Class<T> eventType, EventValidator<T> validator,
            Consumer<T> persistHandler, Function<T, String> correlationIdExtractor) {
        this.objectMapper = objectMapper;
        this.retryTemplate = retryTemplate;
        this.errorTopicPublisher = errorTopicPublisher;
        this.eventType = eventType;
        this.validator = validator;
        this.persistHandler = persistHandler;
        this.correlationIdExtractor = correlationIdExtractor;
    }

    public void process(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        T event;
        try {
            event = parseEvent(record);
        } catch (JsonParseException e) {
            handleParseFailure(record, e);
            acknowledgment.acknowledge();
            return;
        } catch (Exception e) {
            handleMappingFailure(record, e);
            acknowledgment.acknowledge();
            return;
        }

        try {
            validator.validate(event);
        } catch (EventValidationException e) {
            handleValidationFailure(record, event, e);
            acknowledgment.acknowledge();
            return;
        }

        AtomicInteger attempts = new AtomicInteger(0);
        try {
            persistWithRetry(event, attempts);
            handleSuccess(record, attempts.get());
        } catch (TransientProcessingException e) {
            handleTransientFailure(record, event, attempts.get(), e);
        } catch (InboxException e) {
            handleRejectedEvent(record, event, attempts.get(), e);
        } catch (Exception e) {
            handleUnexpectedFailure(record, event, attempts.get(), e);
        } finally {
            acknowledgment.acknowledge();
        }
    }

    private T parseEvent(ConsumerRecord<String, String> record) throws Exception {
        return objectMapper.readValue(record.value(), eventType);
    }

    private void persistWithRetry(T event, AtomicInteger attempts) {
        retryTemplate.execute(context -> {
            attempts.set(context.getRetryCount() + 1);
            try {
                persistHandler.accept(event);
            } catch (DataAccessException e) {
                throw new TransientProcessingException("Technical failure while processing event", e);
            }
            return null;
        });
    }

    private void handleParseFailure(ConsumerRecord<String, String> record, Exception e) {
        log.warn("Discarding unparseable message on topic {} (partition={}, offset={}): {}",
                record.topic(), record.partition(), record.offset(), e.getMessage());
        errorTopicPublisher.publish(record, "VALIDATION_FAILURE",
                "Malformed message payload: " + e.getMessage(), List.of(), 0, null);
    }

    private void handleMappingFailure(ConsumerRecord<String, String> record, Exception e) {
        log.error("Failed to map message on topic {} (partition={}, offset={}) to {} - "
                        + "the JSON is well-formed but doesn't match our DTO, which points to a bug on our side: {}",
                record.topic(), record.partition(), record.offset(), eventType.getSimpleName(), e.getMessage(), e);
        errorTopicPublisher.publish(record, "UNEXPECTED_FAILURE",
                "Failed to deserialize message into " + eventType.getSimpleName() + ": " + e.getMessage(),
                List.of(), 0, null);
    }

    private void handleValidationFailure(ConsumerRecord<String, String> record, T event, EventValidationException e) {
        log.warn("Validation failed for message on topic {} (partition={}, offset={}): {}",
                record.topic(), record.partition(), record.offset(), e.getMessage());
        errorTopicPublisher.publish(record, "VALIDATION_FAILURE", e.getMessage(),
                e.getFieldErrors(), 0, safeCorrelationId(event));
    }

    private void handleSuccess(ConsumerRecord<String, String> record, int attempts) {
        log.info("Processed message on topic {} (partition={}, offset={}) after {} attempt(s)",
                record.topic(), record.partition(), record.offset(), attempts);
    }

    private void handleTransientFailure(ConsumerRecord<String, String> record, T event, int attempts,
            TransientProcessingException e) {
        log.error("Exhausted retries ({}) processing message on topic {} (partition={}, offset={}): {}",
                attempts, record.topic(), record.partition(), record.offset(), e.getMessage());
        errorTopicPublisher.publish(record, "TECHNICAL_FAILURE", e.getMessage(),
                List.of(), attempts, safeCorrelationId(event));
    }

    private void handleRejectedEvent(ConsumerRecord<String, String> record, T event, int attempts,
            InboxException e) {
        log.warn("Rejecting message on topic {} (partition={}, offset={}): {}",
                record.topic(), record.partition(), record.offset(), e.getMessage());
        errorTopicPublisher.publish(record, "VALIDATION_FAILURE", e.getMessage(),
                List.of(e.getErrorCode()), attempts, safeCorrelationId(event));
    }

    private void handleUnexpectedFailure(ConsumerRecord<String, String> record, T event, int attempts, Exception e) {
        log.error("Unexpected failure processing message on topic {} (partition={}, offset={}): {}",
                record.topic(), record.partition(), record.offset(), e.getMessage(), e);
        errorTopicPublisher.publish(record, "UNEXPECTED_FAILURE", String.valueOf(e.getMessage()),
                List.of(), attempts, safeCorrelationId(event));
    }

    private String safeCorrelationId(T event) {
        try {
            return correlationIdExtractor == null ? null : correlationIdExtractor.apply(event);
        } catch (Exception e) {
            return null;
        }
    }
}
