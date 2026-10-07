package com.rnd.chain.events;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaSchedulingUpdatePublisher
        implements SchedulingUpdatePublisher {

    private final String topic;
    private final KafkaTemplate<String, TaskSchedulingUpdatedEvent> kafkaTemplate;

    public KafkaSchedulingUpdatePublisher(
            @Value("${chain.kafka.topics.scheduling-updated:task.scheduling.updated}") String topic,
            KafkaTemplate<String, TaskSchedulingUpdatedEvent> kafkaTemplate) {

        this.topic = topic;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(TaskSchedulingUpdatedEvent event) {

        kafkaTemplate.send(
                topic,
                event.getTaskId().toString(),
                event
        );
    }
}