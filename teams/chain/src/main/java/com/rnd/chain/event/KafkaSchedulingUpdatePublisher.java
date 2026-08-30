package com.rnd.chain.event;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaSchedulingUpdatePublisher
        implements SchedulingUpdatePublisher {

    private static final String TOPIC =
            "task.scheduling.updated";

    private final KafkaTemplate<String, TaskSchedulingUpdatedEvent> kafkaTemplate;

    public KafkaSchedulingUpdatePublisher(
            KafkaTemplate<String, TaskSchedulingUpdatedEvent> kafkaTemplate) {

        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(TaskSchedulingUpdatedEvent event) {

        kafkaTemplate.send(
                TOPIC,
                event.getTaskId().toString(),
                event
        );
    }
}