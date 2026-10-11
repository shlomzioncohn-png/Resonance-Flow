package pipe;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// design doc §00/§01 "Processing Module"
@Component
public class TaskEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(TaskEventProcessor.class);

    private final KafkaTemplate<String, TaskNotification> notificationTemplate;
    private final String notificationTopic;

    public TaskEventProcessor(
            KafkaTemplate<String, TaskNotification> notificationTemplate,
            @Value("${relay.kafka.task-notification-topic}") String notificationTopic) {
        this.notificationTemplate = notificationTemplate;
        this.notificationTopic = notificationTopic;
    }

    @KafkaListener(
            id = "taskEventProcessorListener",
            topics = "${relay.kafka.task-events-notification-topic}",
            containerFactory = "taskNotificationListenerContainerFactory")
    public void consume(TaskNotification notification, ConsumerRecord<String, TaskNotification> record) {
        String key = notification.getOrgId() + ':' + notification.getUserId();
        notificationTemplate.send(notificationTopic, key, notification).whenComplete((result, ex) -> {
            if (ex != null) {
                log.warn("Failed to republish TaskNotification eventId={} taskId={}",
                        notification.getEventId(), notification.getTaskId(), ex);
            }
        });
        log.info("Relayed TaskNotification eventId={} taskId={} partition={} offset={}",
                notification.getEventId(), notification.getTaskId(), record.partition(), record.offset());
    }
}
