package com.rnd.relay.notification_delivery;

import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// Liveness/readiness probes for the pipe. Design doc §05
@RestController
public class HealthController {

    private final KafkaListenerEndpointRegistry listenerRegistry;

    public HealthController(KafkaListenerEndpointRegistry listenerRegistry) {
        this.listenerRegistry = listenerRegistry;
    }

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, String>> healthz() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> readyz() {
        MessageListenerContainer container = listenerRegistry.getListenerContainer("taskNotificationListener");
        boolean ready = container != null && container.isRunning() && !container.getAssignedPartitions().isEmpty();
        return ready
                ? ResponseEntity.ok(Map.of("status", "UP"))
                : ResponseEntity.status(503).body(Map.of("status", "DOWN"));
    }
}
