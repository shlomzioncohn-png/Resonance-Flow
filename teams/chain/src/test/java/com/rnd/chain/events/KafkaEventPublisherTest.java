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
        // does not, so it must be registered explicitly here in case a payload
        // ever carries a java.time field.
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        // consumerGroupId is irrelevant to KafkaEventPublisher (only topics() is
        // read - see its constructor), so it's left null here.
        ChainEventTopicsProperties topicsProperties = new ChainEventTopicsProperties(
                Map.of("task.scheduling.updated", "task.scheduling.updated"), null);

        publisher = new KafkaEventPublisher(kafkaTemplate, objectMapper, topicsProperties);
    }

    @Test
    void publish_sendsSerializedPayloadToConfiguredTopicWithGivenKey() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publish("task.scheduling.updated", "task-1", "payload");

        verify(kafkaTemplate).send(eq("task.scheduling.updated"), eq("task-1"), eq("\"payload\""));
    }

    @Test
    void publish_throwsException_whenNoTopicConfiguredForEventType() {
        assertThrows(EventPublishException.class,
                () -> publisher.publish("unknown.event.type", "task-1", "payload"));
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void publish_throwsEventPublishException_whenTopicsMapIsNull() {
        ChainEventTopicsProperties nullTopics = new ChainEventTopicsProperties(null, null);
        KafkaEventPublisher pub = new KafkaEventPublisher(kafkaTemplate, objectMapper, nullTopics);

        assertThrows(EventPublishException.class,
                () -> pub.publish("task.scheduling.updated", "task-1", "payload"));
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void publish_rejectsNullPayload_beforeTouchingKafka() {
        assertThrows(NullPointerException.class,
                () -> publisher.publish("task.scheduling.updated", "task-1", null));
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void publish_doesNotThrow_whenKafkaSendFailsAsynchronously() {
        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka is down"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failedFuture);

        // An asynchronous send failure should not throw immediately - it is only logged
        assertDoesNotThrow(() -> publisher.publish("task.scheduling.updated", "task-1", "payload"));
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

        assertThrows(EventPublishException.class,
                () -> publisher.publish("task.scheduling.updated", "task-1", payloadThatFailsToSerialize));
        verifyNoInteractions(kafkaTemplate);
    }
}
