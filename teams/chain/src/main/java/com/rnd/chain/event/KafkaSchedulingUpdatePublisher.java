package com.rnd.chain.event;

import com.rnd.chain.events.ChainEventTopicsProperties;
import com.rnd.inbox.event.SchedulingUpdatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KafkaSchedulingUpdatePublisher
        implements SchedulingUpdatePublisher {

    private static final String TOPIC_KEY = "task.scheduling.updated";

    private final KafkaTemplate<String, SchedulingUpdatedEvent> kafkaTemplate;
    private final String topic;

    public KafkaSchedulingUpdatePublisher(
            KafkaTemplate<String, SchedulingUpdatedEvent> kafkaTemplate,
            ChainEventTopicsProperties topicsProperties) {

        this.kafkaTemplate = kafkaTemplate;
        this.topic = topicsProperties.topics() == null ? null : topicsProperties.topics().get(TOPIC_KEY);
        if (topic == null || topic.isBlank()) {
            throw new IllegalStateException("Missing config: chain.kafka.topics.\"" + TOPIC_KEY + "\"");
        }
    }

    @Override
    public void publish(TaskSchedulingUpdatedEvent event) {

        SchedulingUpdatedEvent proto = toProto(event);
        try {
            kafkaTemplate.send(topic, event.getTaskId().toString(), proto)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            logFailure(proto, ex);
                        }
                    });
        } catch (RuntimeException ex) {
            // Serialization (Schema Registry) failures are thrown synchronously from send().
            logFailure(proto, ex);
            throw ex;
        }
    }

    private static void logFailure(SchedulingUpdatedEvent proto, Throwable ex) {
        log.error("Failed to publish task.scheduling.updated eventId={} taskId={}",
                proto.getEventId(), proto.getTaskId(), ex);
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
