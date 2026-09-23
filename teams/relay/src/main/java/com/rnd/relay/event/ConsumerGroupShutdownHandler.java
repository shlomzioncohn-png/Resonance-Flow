package com.rnd.relay.event;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.Admin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class ConsumerGroupShutdownHandler {

    private static final Logger log = LoggerFactory.getLogger(ConsumerGroupShutdownHandler.class);

    private final KafkaProperties kafkaProperties;
    private final String consumerGroupId;

    public ConsumerGroupShutdownHandler(
            KafkaProperties kafkaProperties,
            @Value("${relay.pipe.consumer-group-id}") String consumerGroupId) {
        this.kafkaProperties = kafkaProperties;
        this.consumerGroupId = consumerGroupId;
    }

    @PreDestroy
    void deleteOwnConsumerGroup() {
        try (Admin admin = Admin.create(kafkaProperties.buildAdminProperties(null))) {
            admin.deleteConsumerGroups(List.of(consumerGroupId)).all().get(10, TimeUnit.SECONDS);
            log.info("Deleted consumer group {} on shutdown", consumerGroupId);
        } catch (Exception e) {
            log.warn("Failed to delete consumer group {} on shutdown — it will be left as an orphan on the broker",
                    consumerGroupId, e);
        }
    }
}
