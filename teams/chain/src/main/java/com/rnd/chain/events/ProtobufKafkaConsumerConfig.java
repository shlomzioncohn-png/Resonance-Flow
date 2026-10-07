package com.rnd.chain.events;

import com.rnd.inbox.event.TaskCreatedEvent;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializerConfig;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class ProtobufKafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TaskCreatedEvent>
            protobufKafkaListenerContainerFactory(
                    @Value("${spring.kafka.bootstrap-servers}")
                    String bootstrapServers,

                    @Value("${chain.kafka.consumer-group-id}")
                    String groupId,

                    @Value("${spring.kafka.properties.schema.registry.url}")
                    String schemaRegistryUrl,
                    @Qualifier("protobufKafkaErrorHandler")
                    DefaultErrorHandler errorHandler
                ) {

        Map<String, Object> props = new HashMap<>();

        props.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers);

        props.put(
                ConsumerConfig.GROUP_ID_CONFIG,
                groupId);

        props.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);

        props.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                KafkaProtobufDeserializer.class);

        props.put(
                "schema.registry.url",
                schemaRegistryUrl);

        props.put(
                KafkaProtobufDeserializerConfig.SPECIFIC_PROTOBUF_VALUE_TYPE,
                TaskCreatedEvent.class.getName());

        DefaultKafkaConsumerFactory<String, TaskCreatedEvent>
                consumerFactory =
                new DefaultKafkaConsumerFactory<>(props);

        ConcurrentKafkaListenerContainerFactory<String, TaskCreatedEvent>
                factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }

    @Bean
public KafkaTemplate<String, Object> protobufDltKafkaTemplate(
        @Value("${spring.kafka.bootstrap-servers}")
        String bootstrapServers,

        @Value("${spring.kafka.properties.schema.registry.url}")
        String schemaRegistryUrl) {

    Map<String, Object> props = new HashMap<>();

    props.put(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
            bootstrapServers);

    props.put(
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class);

    props.put(
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            KafkaProtobufSerializer.class);

    props.put(
            "schema.registry.url",
            schemaRegistryUrl);

    return new KafkaTemplate<>(
            new DefaultKafkaProducerFactory<>(props));
}

@Bean
public DefaultErrorHandler protobufKafkaErrorHandler(
        @Qualifier("protobufDltKafkaTemplate")
        KafkaTemplate<String, Object> kafkaTemplate,
        ChainKafkaRetryProperties retryProperties) {

    DeadLetterPublishingRecoverer recoverer =
            new DeadLetterPublishingRecoverer(
                    kafkaTemplate);

    FixedBackOff backOff =
            new FixedBackOff(
                    retryProperties.intervalMs(),
                    retryProperties.maxAttempts());

    DefaultErrorHandler errorHandler =
            new DefaultErrorHandler(
                    recoverer,
                    backOff);

    errorHandler.addNotRetryableExceptions(
            NonRetryableEventException.class);

    return errorHandler;
}

}