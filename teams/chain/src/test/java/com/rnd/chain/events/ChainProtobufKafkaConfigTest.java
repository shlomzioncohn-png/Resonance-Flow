package com.rnd.chain.events;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChainProtobufKafkaConfigTest {

    private final ChainProtobufKafkaConfig config = new ChainProtobufKafkaConfig();

    ChainProtobufKafkaConfigTest() {
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "schemaRegistryUrl", "mock://test");
    }

    @Test
    void consumerFactories_disableAutoCommit() {
        assertEquals(Boolean.FALSE, config.taskCreatedEventConsumerFactory()
                .getConfigurationProperties().get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
        assertEquals(Boolean.FALSE, config.taskUpdatedEventConsumerFactory()
                .getConfigurationProperties().get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
        assertEquals(Boolean.FALSE, config.dependencyProposedEventConsumerFactory()
                .getConfigurationProperties().get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
    }

    @Test
    void listenerContainerFactories_commitPerRecord() {
        assertEquals(ContainerProperties.AckMode.RECORD, config.taskCreatedEventKafkaListenerContainerFactory(
                config.taskCreatedEventConsumerFactory()).getContainerProperties().getAckMode());
        assertEquals(ContainerProperties.AckMode.RECORD, config.taskUpdatedEventKafkaListenerContainerFactory(
                config.taskUpdatedEventConsumerFactory()).getContainerProperties().getAckMode());
        assertEquals(ContainerProperties.AckMode.RECORD, config.dependencyProposedEventKafkaListenerContainerFactory(
                config.dependencyProposedEventConsumerFactory()).getContainerProperties().getAckMode());
    }
}
