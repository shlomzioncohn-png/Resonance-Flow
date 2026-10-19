package com.rnd.chain.events;

import com.rnd.inbox.event.SchedulingUpdatedEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaSchedulingUpdatePublisherTest {

    private KafkaTemplate<String, SchedulingUpdatedEvent> kafkaTemplate;
    private KafkaSchedulingUpdatePublisher publisher;

    @BeforeEach
    void setUp() {

        kafkaTemplate = mock(KafkaTemplate.class);

        ChainEventTopicsProperties properties =
                new ChainEventTopicsProperties(
                        Map.of(
                                "scheduling-updated",
                                "task.scheduling.updated"),
                        "chain-service");

        when(kafkaTemplate.send(
                anyString(),
                anyString(),
                any(SchedulingUpdatedEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher = new KafkaSchedulingUpdatePublisher(
                kafkaTemplate,
                properties);
    }

    @Test
    void publish_shouldSendProtobufSchedulingUpdatedEvent() {

        UUID eventId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        TaskSchedulingUpdatedEvent event =
                new TaskSchedulingUpdatedEvent(
                        eventId,
                        taskId,
                        true,
                        7.5);

        publisher.publish(event);

        ArgumentCaptor<String> keyCaptor =
                ArgumentCaptor.forClass(String.class);

        ArgumentCaptor<SchedulingUpdatedEvent> valueCaptor =
                ArgumentCaptor.forClass(
                        SchedulingUpdatedEvent.class);

        verify(kafkaTemplate).send(
                eq("task.scheduling.updated"),
                keyCaptor.capture(),
                valueCaptor.capture());

        assertEquals(
                taskId.toString(),
                keyCaptor.getValue());

        SchedulingUpdatedEvent proto =
                valueCaptor.getValue();

        assertEquals(
                eventId.toString(),
                proto.getEventId());

        assertEquals(
                taskId.toString(),
                proto.getTaskId());

        assertTrue(proto.getAvailable());

        assertTrue(proto.hasPriorityScore());

        assertEquals(
                7.5,
                proto.getPriorityScore());
    }

    @Test
    void publish_shouldOmitPriorityScore_whenNull() {

        UUID eventId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        TaskSchedulingUpdatedEvent event =
                new TaskSchedulingUpdatedEvent(
                        eventId,
                        taskId,
                        false,
                        null);

        publisher.publish(event);

        ArgumentCaptor<SchedulingUpdatedEvent> valueCaptor =
                ArgumentCaptor.forClass(
                        SchedulingUpdatedEvent.class);

        verify(kafkaTemplate).send(
                eq("task.scheduling.updated"),
                eq(taskId.toString()),
                valueCaptor.capture());

        SchedulingUpdatedEvent proto =
                valueCaptor.getValue();

        assertFalse(proto.getAvailable());
        assertFalse(proto.hasPriorityScore());
    }
}