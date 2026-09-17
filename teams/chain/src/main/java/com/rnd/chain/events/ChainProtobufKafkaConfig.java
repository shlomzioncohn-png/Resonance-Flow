package com.rnd.chain.events;

import com.rnd.inbox.event.TaskCreatedEvent;
import com.rnd.inbox.event.TaskUpdatedEvent;
import com.rnd.mind.event.DependencyProposedEvent;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializerConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer factories for Chain's Protobuf-based inbound topics: task.created, task.updated
 * (both Inbox), and task.dependencies.proposed (Mind). One factory per message type - Confluent's
 * KafkaProtobufDeserializer needs the concrete target class per consumer
 * (specific.protobuf.value.type), and each of these three topics carries a different message.
 *
 * ErrorHandlingDeserializer wraps the real deserializer so a message that fails to deserialize
 * (wrong schema, corrupt bytes) surfaces as a DeserializationException the listener container
 * routes through the usual error-handling path, instead of killing the container outright.
 */
@Configuration
public class ChainProtobufKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${chain.kafka.schema-registry-url}")
    private String schemaRegistryUrl;

    private Map<String, Object> consumerProps(Class<?> specificProtobufType) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, KafkaProtobufDeserializer.class);
        props.put(KafkaProtobufDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        props.put(KafkaProtobufDeserializerConfig.SPECIFIC_PROTOBUF_VALUE_TYPE, specificProtobufType);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }

    @Bean
    public ConsumerFactory<String, TaskCreatedEvent> taskCreatedEventConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(consumerProps(TaskCreatedEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TaskCreatedEvent> taskCreatedEventKafkaListenerContainerFactory(
            ConsumerFactory<String, TaskCreatedEvent> taskCreatedEventConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, TaskCreatedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(taskCreatedEventConsumerFactory);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, TaskUpdatedEvent> taskUpdatedEventConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(consumerProps(TaskUpdatedEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TaskUpdatedEvent> taskUpdatedEventKafkaListenerContainerFactory(
            ConsumerFactory<String, TaskUpdatedEvent> taskUpdatedEventConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, TaskUpdatedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(taskUpdatedEventConsumerFactory);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, DependencyProposedEvent> dependencyProposedEventConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(consumerProps(DependencyProposedEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DependencyProposedEvent> dependencyProposedEventKafkaListenerContainerFactory(
            ConsumerFactory<String, DependencyProposedEvent> dependencyProposedEventConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, DependencyProposedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(dependencyProposedEventConsumerFactory);
        return factory;
    }
}
