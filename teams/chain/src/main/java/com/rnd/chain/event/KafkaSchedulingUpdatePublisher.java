package com.rnd.chain.event;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaSchedulingUpdatePublisher
        implements SchedulingUpdatePublisher {

    private static final String TOPIC =
            "task.scheduling.updated";

    private final KafkaTemplate<String, SchedulingUpdatedEvent> kafkaTemplate;

    public KafkaSchedulingUpdatePublisher(
            KafkaTemplate<String, SchedulingUpdatedEvent> kafkaTemplate) {

        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(TaskSchedulingUpdatedEvent event) {

        kafkaTemplate.send(
                TOPIC,
                event.getTaskId().toString(),
                toProto(event)
        );
    }

    static SchedulingUpdatedEvent toProto(TaskSchedulingUpdatedEvent event) {
        SchedulingUpdatedEvent.Builder builder = SchedulingUpdatedEvent.newBuilder()
                .setEventId(event.getEventId().toString())
                .setTaskId(event.getTaskId().toString())
                .setAvailable(event.isAvailable());
        // Absent score means "leave Inbox's stored value unchanged", so it must not be sent as 0.
        if (event.getPriorityScore() != null) {
            builder.setPriorityScore(event.getPriorityScore());
        }
        return builder.build();
    }
}
