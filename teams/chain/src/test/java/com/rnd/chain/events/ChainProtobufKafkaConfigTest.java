package com.rnd.chain.events;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChainProtobufKafkaConfigTest {

    private final KafkaProperties kafkaProperties = new KafkaProperties();
    private final ChainProtobufKafkaConfig config = new ChainProtobufKafkaConfig(kafkaProperties);

    ChainProtobufKafkaConfigTest() {
        kafkaProperties.setBootstrapServers(List.of("localhost:9092"));
        ReflectionTestUtils.setField(config, "schemaRegistryUrl", "mock://test");
    }

    @Test
    void securityPropertiesFromSpringKafkaReachEveryConsumerAndProducer() {
        kafkaProperties.getProperties().put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT");
        kafkaProperties.getProperties().put(SaslConfigs.SASL_MECHANISM, "PLAIN");

        assertEquals("SASL_PLAINTEXT", config.taskCreatedEventConsumerFactory()
                .getConfigurationProperties().get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals("SASL_PLAINTEXT", config.taskUpdatedEventConsumerFactory()
                .getConfigurationProperties().get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals("SASL_PLAINTEXT", config.dependencyProposedEventConsumerFactory()
                .getConfigurationProperties().get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals("SASL_PLAINTEXT", config.kafkaTemplate().getProducerFactory()
                .getConfigurationProperties().get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals("SASL_PLAINTEXT", config.taskCreatedEventKafkaTemplate().getProducerFactory()
                .getConfigurationProperties().get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals(List.of("localhost:9092"), config.kafkaTemplate().getProducerFactory()
                .getConfigurationProperties().get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
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
