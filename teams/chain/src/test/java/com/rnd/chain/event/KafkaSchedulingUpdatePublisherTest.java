package com.rnd.chain.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaSchedulingUpdatePublisherTest {

    private KafkaTemplate<String, String> kafkaTemplate;
    private ObjectMapper objectMapper;
    private KafkaSchedulingUpdatePublisher publisher;

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        objectMapper = new ObjectMapper();

        publisher = new KafkaSchedulingUpdatePublisher(
                kafkaTemplate,
                objectMapper,
                "task.scheduling.updated");
    }

    @Test
    void publish_shouldSendFlatInboxContract() throws Exception {

        UUID taskId = UUID.randomUUID();

        TaskSchedulingUpdatedEvent event =
                new TaskSchedulingUpdatedEvent(
                        taskId,
                        true,
                        7.5);

        publisher.publish(event);

        ArgumentCaptor<String> keyCaptor =
                ArgumentCaptor.forClass(String.class);

        ArgumentCaptor<String> valueCaptor =
                ArgumentCaptor.forClass(String.class);

        verify(kafkaTemplate).send(
                eq("task.scheduling.updated"),
                keyCaptor.capture(),
                valueCaptor.capture());

        assertEquals(
                taskId.toString(),
                keyCaptor.getValue());

        JsonNode json =
                objectMapper.readTree(valueCaptor.getValue());

        assertTrue(json.hasNonNull("eventId"));
        assertDoesNotThrow(
                () -> UUID.fromString(
                        json.get("eventId").asText()));

        assertEquals(
                taskId.toString(),
                json.get("taskId").asText());

        assertTrue(
                json.get("isAvailable").asBoolean());

        assertEquals(
                7.5,
                json.get("priorityScore").asDouble());

        assertFalse(json.has("payload"));
    }
}