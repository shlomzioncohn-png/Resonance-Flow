package pipe;

import com.rnd.relay.event.PushPolicy;
import com.rnd.relay.event.TaskNotification;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskEventProcessorTest {

    private static final String NOTIFICATION_TOPIC = "resonance.task-notifications";

    @Mock
    KafkaTemplate<String, TaskNotification> notificationTemplate;

    @Test
    void consumingAnEventRepublishesTheExactSameNotificationKeyedByOrgAndUser() {
        when(notificationTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        TaskEventProcessor processor = new TaskEventProcessor(notificationTemplate, NOTIFICATION_TOPIC);
        TaskNotification notification = TaskNotification.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOrgId("org-1")
                .setUserId("user-1")
                .setTaskId("task-1")
                .setPushPolicy(PushPolicy.ACTIVITY)
                .build();

        processor.consume(notification, consumerRecord(notification));

        ArgumentCaptor<TaskNotification> sent = ArgumentCaptor.forClass(TaskNotification.class);
        verify(notificationTemplate).send(eq(NOTIFICATION_TOPIC), eq("org-1:user-1"), sent.capture());
        assertThat(sent.getValue()).isEqualTo(notification);
    }

    private static ConsumerRecord<String, TaskNotification> consumerRecord(TaskNotification notification) {
        return new ConsumerRecord<>("resonance.task-events-notifications", 0, 0L,
                notification.getOrgId() + ':' + notification.getUserId(), notification);
    }
}
