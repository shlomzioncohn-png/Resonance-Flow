package com.rnd.chain.events;

import com.google.protobuf.Message;
import com.rnd.chain.event.SchedulingUpdatedEvent;
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

    private KafkaTemplate<String, Message> kafkaTemplate;
    private KafkaEventPublisher publisher;
    private final SchedulingUpdatedEvent payload = SchedulingUpdatedEvent.newBuilder()
            .setEventId("e-1").setTaskId("task-1").setAvailable(true).build();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        // consumerGroupId is irrelevant to KafkaEventPublisher (only topics() is
        // read - see its constructor), so it's left null here.
        ChainEventTopicsProperties topicsProperties = new ChainEventTopicsProperties(
                Map.of("task.scheduling.updated", "task.scheduling.updated"), null);

        publisher = new KafkaEventPublisher(kafkaTemplate, topicsProperties);
    }

    @Test
    void publish_sendsPayloadToConfiguredTopicWithGivenKey() {
        when(kafkaTemplate.send(anyString(), anyString(), any(Message.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publish("task.scheduling.updated", "task-1", payload);

        verify(kafkaTemplate).send(eq("task.scheduling.updated"), eq("task-1"), eq(payload));
    }

    @Test
    void publish_throwsException_whenNoTopicConfiguredForEventType() {
        assertThrows(EventPublishException.class,
                () -> publisher.publish("unknown.event.type", "task-1", payload));
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void publish_throwsEventPublishException_whenTopicsMapIsNull() {
        ChainEventTopicsProperties nullTopics = new ChainEventTopicsProperties(null, null);
        KafkaEventPublisher pub = new KafkaEventPublisher(kafkaTemplate, nullTopics);

        assertThrows(EventPublishException.class,
                () -> pub.publish("task.scheduling.updated", "task-1", payload));
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
        CompletableFuture<SendResult<String, Message>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka is down"));
        when(kafkaTemplate.send(anyString(), anyString(), any(Message.class))).thenReturn(failedFuture);

        // An asynchronous send failure should not throw immediately - it is only logged
        assertDoesNotThrow(() -> publisher.publish("task.scheduling.updated", "task-1", payload));
    }
}
