package com.rnd.relay.event;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = { "task-event", "resonance.task-notifications" })
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.properties.schema.registry.url=mock://consumer-group-shutdown-handler-tests"
})
class ConsumerGroupShutdownHandlerTest {

    @Autowired
    private ConsumerGroupShutdownHandler handler;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Value("${relay.pipe.consumer-group-id}")
    private String consumerGroupId;

    @Test
    void deletesItsOwnConsumerGroupOnceItIsEmpty() {
        MessageListenerContainer container = registry.getListenerContainer("taskNotificationListener");
        ContainerTestUtils.waitForAssignment(container, embeddedKafkaBroker.getPartitionsPerTopic());
        // Mirrors real shutdown order (design doc §06.7): Spring stops @KafkaListener containers —
        // which makes the consumer leave the group — *before* @PreDestroy beans run, so the group
        // is already empty by the time deleteOwnConsumerGroup() is called.
        container.stop();

        handler.deleteOwnConsumerGroup();

        try (Admin admin = adminClient()) {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(admin.listConsumerGroups().all().get())
                            .noneMatch(group -> group.groupId().equals(consumerGroupId)));
        }
    }

    @Test
    void aBrokerFailureIsLoggedNotThrown() {
        KafkaProperties unreachableProperties = new KafkaProperties();
        unreachableProperties.setBootstrapServers(List.of("localhost:1"));
        ConsumerGroupShutdownHandler unreachable =
                new ConsumerGroupShutdownHandler(unreachableProperties, "some-group");

        assertThatCode(unreachable::deleteOwnConsumerGroup).doesNotThrowAnyException();
    }

    private Admin adminClient() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBrokersAsString()));
    }
}
