package com.rnd.inbox.shared.event;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, Object> protobufKafkaTemplate;
    private final Map<EventType, ProtoEventMapper<?>> protoMappersByType;
    private final ScheduledExecutorService retryScheduler;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final double backoffMultiplier;
    private final long maxBackoffMs;

    public KafkaEventPublisher(
            @Qualifier("protobufKafkaTemplate") KafkaTemplate<String, Object> protobufKafkaTemplate,
            List<ProtoEventMapper<?>> protoMappers,
            @Qualifier("retryScheduler") ScheduledExecutorService retryScheduler,
            @Value("${kafka.producer.retry.max-attempts:3}") int maxAttempts,
            @Value("${kafka.producer.retry.initial-backoff-ms:200}") long initialBackoffMs,
            @Value("${kafka.producer.retry.backoff-multiplier:2.0}") double backoffMultiplier,
            @Value("${kafka.producer.retry.max-backoff-ms:5000}") long maxBackoffMs) {
        this.protobufKafkaTemplate = protobufKafkaTemplate;
        this.protoMappersByType = protoMappers.stream()
                .collect(Collectors.toMap(ProtoEventMapper::supportedType, m -> m));
        this.retryScheduler = retryScheduler;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.backoffMultiplier = backoffMultiplier;
        this.maxBackoffMs = maxBackoffMs;
    }

    @Override
    public <T> CompletableFuture<Void> publish(String topic, String key, Event<T> event) {
        CompletableFuture<Void> resultFuture = new CompletableFuture<>();
        ProducerRecord<String, Object> record = buildRecord(topic, key, event);
        attemptSend(record, event, topic, key, 1, resultFuture);
        return resultFuture;
    }

    private <T> void attemptSend(ProducerRecord<String, Object> record, Event<T> event, String topic, String key,
                                  int attempt, CompletableFuture<Void> resultFuture) {
        CompletableFuture<SendResult<String, Object>> future = protobufKafkaTemplate.send(record);

        future.whenComplete((sendResult, ex) -> {
            if (ex == null) {
                log.debug("Published event {} (type={}) to topic {} on attempt {}",
                        event.getEventId(), event.getEventType().getTopic(), topic, attempt);
                resultFuture.complete(null);
            } else {
                handleFailure(record, event, topic, key, attempt, resultFuture, ex);
            }
        });
    }

    private <T> void handleFailure(ProducerRecord<String, Object> record, Event<T> event, String topic, String key,
                                    int attempt, CompletableFuture<Void> resultFuture, Throwable ex) {
        if (isRetryable(ex) && attempt < maxAttempts) {
            long delayMs = computeBackoffMs(attempt);
            log.warn("Publish attempt {} failed for event {} (type={}) to topic {}; retrying in {} ms",
                    attempt, event.getEventId(), event.getEventType().getTopic(), topic, delayMs, ex);
            retryScheduler.schedule(
                    () -> attemptSend(record, event, topic, key, attempt + 1, resultFuture),
                    delayMs, TimeUnit.MILLISECONDS);
        } else {
            log.error("Failed to publish event after {} attempt(s). eventType={} topic={} eventId={} task_id={} lastError={}",
                    attempt, event.getEventType().getTopic(), topic, event.getEventId(), key, ex.getMessage(), ex);
            resultFuture.completeExceptionally(
                    new EventPublishException("Failed to publish event " + event.getEventId() + " to topic " + topic, ex));
        }
    }

    private boolean isRetryable(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof RetriableException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private long computeBackoffMs(int attempt) {
        double delay = initialBackoffMs * Math.pow(backoffMultiplier, attempt - 1);
        return (long) Math.min(delay, maxBackoffMs);
    }

    private <T> ProducerRecord<String, Object> buildRecord(String topic, String key, Event<T> event) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, toProtoValue(event));
        Headers headers = record.headers();
        headers.add("event_id", event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
        headers.add("event_type", event.getEventType().getTopic().getBytes(StandardCharsets.UTF_8));
        headers.add("timestamp", event.getTimestamp().toString().getBytes(StandardCharsets.UTF_8));
        headers.add("source_service", event.getSourceService().getBytes(StandardCharsets.UTF_8));
        if (event.getCorrelationId() != null) {
            headers.add("correlation_id", event.getCorrelationId().toString().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    private <T> Object toProtoValue(Event<T> event) {
        ProtoEventMapper<?> mapper = protoMappersByType.get(event.getEventType());
        if (mapper == null) {
            throw new IllegalStateException(
                    "No ProtoEventMapper registered for event type " + event.getEventType());
        }
        if (!(event.getPayload() instanceof Map)) {
            throw new IllegalStateException("Expected a Map<String, Object> payload for event type "
                    + event.getEventType() + " but got " + event.getPayload().getClass());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = (Map<String, Object>) event.getPayload();
        return mapper.toProto(payloadMap);
    }
}
