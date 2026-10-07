package com.rnd.chain.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.chain.events.EventPublishException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class KafkaSchedulingUpdatePublisher
        implements SchedulingUpdatePublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public KafkaSchedulingUpdatePublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${chain.kafka.topics.task.scheduling.updated}")
            String topic) {

        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void publish(TaskSchedulingUpdatedEvent event) {

        UUID eventId = UUID.randomUUID();

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("eventId", eventId.toString());
        message.put("taskId", event.getTaskId().toString());
        message.put("isAvailable", event.isAvailable());
        message.put("priorityScore", event.getPriorityScore());

        final String serialized;

        try {
            serialized = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new EventPublishException(
                    "Failed to serialize task.scheduling.updated event", e);
        }

        kafkaTemplate.send(
                topic,
                event.getTaskId().toString(),
                serialized);
    }
}