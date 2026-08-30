package com.rnd.inbox.messaging.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class ErrorTopicPublisher {

    private static final String ERROR_TOPIC_SUFFIX = ".errors";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ErrorTopicPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper, Clock clock) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void publish(ConsumerRecord<String, String> record, String errorType, String errorMessage,
            List<String> errorDetails, int retryCount, String correlationId) {
        String errorTopic = record.topic() + ERROR_TOPIC_SUFFIX;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originalTopic", record.topic());
        body.put("originalPartition", record.partition());
        body.put("originalOffset", record.offset());
        body.put("originalPayload", record.value());
        body.put("errorType", errorType);
        body.put("errorMessage", errorMessage);
        body.put("errorDetails", errorDetails == null ? List.of() : errorDetails);
        body.put("retryCount", retryCount);
        body.put("timestamp", Instant.now(clock).toString());
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }

        String json = toJson(errorType, errorMessage, body);

        ProducerRecord<String, String> errorRecord = new ProducerRecord<>(errorTopic, record.key(), json);
        addHeader(errorRecord, "x-original-topic", record.topic());
        addHeader(errorRecord, "x-error-type", errorType);
        addHeader(errorRecord, "x-retry-count", String.valueOf(retryCount));
        if (correlationId != null) {
            addHeader(errorRecord, "x-correlation-id", correlationId);
        }

        try {
            kafkaTemplate.send(errorRecord).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing to error topic " + errorTopic, e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish to error topic " + errorTopic, e);
        }
    }

    private String toJson(String errorType, String errorMessage, Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            return "{\"errorType\":\"" + errorType + "\",\"errorMessage\":\"" + errorMessage + "\"}";
        }
    }

    private void addHeader(ProducerRecord<String, String> record, String key, String value) {
        record.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
    }
}
