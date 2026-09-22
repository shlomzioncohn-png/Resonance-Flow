package com.rnd.relay.notification_delivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    private static final String LISTENER_ID = "taskNotificationListener";

    @Mock
    KafkaListenerEndpointRegistry listenerRegistry;

    @Mock
    MessageListenerContainer container;

    @Test
    void healthzIsAlwaysUp() {
        HealthController controller = new HealthController(listenerRegistry);

        ResponseEntity<?> response = controller.healthz();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void readyzIsDownWhenNoListenerContainerExists() {
        when(listenerRegistry.getListenerContainer(LISTENER_ID)).thenReturn(null);
        HealthController controller = new HealthController(listenerRegistry);

        ResponseEntity<?> response = controller.readyz();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void readyzIsDownWhenContainerIsNotRunning() {
        when(listenerRegistry.getListenerContainer(LISTENER_ID)).thenReturn(container);
        when(container.isRunning()).thenReturn(false);
        HealthController controller = new HealthController(listenerRegistry);

        ResponseEntity<?> response = controller.readyz();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void readyzIsDownWhenNoPartitionsAreAssignedYet() {
        when(listenerRegistry.getListenerContainer(LISTENER_ID)).thenReturn(container);
        when(container.isRunning()).thenReturn(true);
        when(container.getAssignedPartitions()).thenReturn(Set.of());
        HealthController controller = new HealthController(listenerRegistry);

        ResponseEntity<?> response = controller.readyz();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void readyzIsUpWhenTheContainerIsRunningWithAnAssignedPartition() {
        when(listenerRegistry.getListenerContainer(LISTENER_ID)).thenReturn(container);
        when(container.isRunning()).thenReturn(true);
        when(container.getAssignedPartitions()).thenReturn(Set.of(new org.apache.kafka.common.TopicPartition("resonance.task-notifications", 0)));
        HealthController controller = new HealthController(listenerRegistry);

        ResponseEntity<?> response = controller.readyz();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
