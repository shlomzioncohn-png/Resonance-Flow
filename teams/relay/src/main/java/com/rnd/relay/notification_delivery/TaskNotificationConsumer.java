package com.rnd.relay.notification_delivery;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TaskNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(TaskNotificationConsumer.class);

    private final NotificationRouter router;

    public TaskNotificationConsumer(NotificationRouter router) {
        this.router = router;
    }

    @KafkaListener(
            id = "taskNotificationListener",
            topics = "${relay.kafka.task-notification-topic}",
            groupId = "${relay.pipe.consumer-group-id}",
            containerFactory = "taskNotificationListenerContainerFactory")
    public void consume(TaskNotification event, ConsumerRecord<String, TaskNotification> record) {
        log.info("Received task notification eventId={} type={} userId={} partition={} offset={}",
                event.getEventId(), event.getUserId(),
                record.partition(), record.offset());
        router.handle(event, record.offset(), Instant.now());
    }
}
