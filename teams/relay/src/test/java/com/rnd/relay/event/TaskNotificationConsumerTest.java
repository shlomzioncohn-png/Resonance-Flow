package com.rnd.relay.event;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TaskNotificationConsumerTest {

    @Mock
    NotificationRouter router;

    @Test
    void handsTheRecordsOffsetAndCurrentTimeToTheRouter() {
        TaskNotificationConsumer consumer = new TaskNotificationConsumer(router);
        TaskNotification event = TaskNotification.newBuilder()
                .setEventId("event-1")
                .setOrgId("org-1")
                .setUserId("user-1")
                .setTaskId("task-1")
                .setPushPolicy(PushPolicy.ACTIVITY)
                .build();
        ConsumerRecord<String, TaskNotification> record =
                new ConsumerRecord<>("resonance.task-notifications", 0, 42L, "org-1:user-1", event);

        Instant before = Instant.now();
        consumer.consume(event, record);
        Instant after = Instant.now();

        ArgumentCaptor<Long> offsetCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(router).handle(org.mockito.ArgumentMatchers.eq(event), offsetCaptor.capture(), nowCaptor.capture());

        assertThat(offsetCaptor.getValue()).isEqualTo(42L);
        assertThat(nowCaptor.getValue()).isBetween(before.minus(Duration.ofSeconds(1)), after.plus(Duration.ofSeconds(1)));
    }
}
