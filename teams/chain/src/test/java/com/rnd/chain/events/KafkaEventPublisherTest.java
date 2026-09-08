package com.rnd.chain.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KafkaEventPublisherTest {

    private KafkaTemplate<String, String> kafkaTemplate;
    private ObjectMapper objectMapper;
    private KafkaEventPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        // Spring Boot's autoconfigured ObjectMapper registers JavaTimeModule
        // automatically in production. A manually constructed ObjectMapper
        // does not, so it must be registered explicitly here to serialize
        // Instant fields (e.g. EventEnvelope.occurredAt).
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        // consumedTopics/consumerGroupId are irrelevant to KafkaEventPublisher
        // (only topics() is read - see its constructor), so they're left null here.
        ChainEventTopicsProperties topicsProperties = new ChainEventTopicsProperties(
                Map.of("task.scheduling.updated", "task.scheduling.updated"), null, null);

        publisher = new KafkaEventPublisher(kafkaTemplate, objectMapper, topicsProperties);
    }

    @Test
    void publish_sendsSerializedEventToConfiguredTopic() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        EventEnvelope<String> event = new EventEnvelope<>("task.scheduling.updated", "corr-1", "payload");

        publisher.publish(event);

        verify(kafkaTemplate).send(eq("task.scheduling.updated"), eq(event.getEventId().toString()), anyString());
    }

    @Test
    void publish_throwsException_whenNoTopicConfiguredForEventType() {
        EventEnvelope<String> event = new EventEnvelope<>("unknown.event.type", "corr-1", "payload");

        assertThrows(EventPublishException.class, () -> publisher.publish(event));
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void publish_throwsEventPublishException_whenTopicsMapIsNull() {
        ChainEventTopicsProperties nullTopics = new ChainEventTopicsProperties(null, null, null);
        KafkaEventPublisher pub = new KafkaEventPublisher(kafkaTemplate, objectMapper, nullTopics);
        EventEnvelope<String> event = new EventEnvelope<>("task.scheduling.updated", "corr-1", "payload");

        assertThrows(EventPublishException.class, () -> pub.publish(event));
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void publish_doesNotThrow_whenKafkaSendFailsAsynchronously() {
        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka is down"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failedFuture);

        EventEnvelope<String> event = new EventEnvelope<>("task.scheduling.updated", "corr-1", "payload");

        // An asynchronous send failure should not throw immediately - it is only logged
        assertDoesNotThrow(() -> publisher.publish(event));
    }

    @Test
    void publish_throwsException_whenSerializationFails() {
        // A payload whose getter throws during Jackson's introspection -
        // KafkaEventPublisher.serialize()'s objectMapper.writeValueAsString(...)
        // call fails, which must surface as EventPublishException rather than
        // a raw Jackson exception, and must never reach kafkaTemplate.send(...).
        Object payloadThatFailsToSerialize = new Object() {
            @SuppressWarnings("unused")
            public String getValue() {
                throw new RuntimeException("boom");
            }
        };
        EventEnvelope<Object> event = new EventEnvelope<>(
                "task.scheduling.updated", "corr-1", payloadThatFailsToSerialize);

        assertThrows(EventPublishException.class, () -> publisher.publish(event));
        verifyNoInteractions(kafkaTemplate);
    }
}
