package com.rnd.inbox.shared.config;

import com.rnd.inbox.event.SchedulingUpdatedEvent;
import com.rnd.inbox.event.StructuredTaskEvent;
import com.rnd.inbox.event.TaskCreationRequestedEvent;
import com.rnd.inbox.shared.messaging.kafka.ErrorTopicPublisher;
import com.rnd.inbox.shared.messaging.kafka.TransientProcessingException;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializerConfig;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.backoff.FixedBackOff;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
@EnableConfigurationProperties(KafkaRetryProperties.class)
public class KafkaConfig {

    @Bean
    public ProducerFactory<String, String> inboxKafkaProducerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaProducerFactory<>(
                kafkaProperties.buildProducerProperties(), new StringSerializer(), new StringSerializer());
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> inboxKafkaProducerFactory) {
        return new KafkaTemplate<>(inboxKafkaProducerFactory);
    }

    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ProducerFactory<String, Object> protobufKafkaProducerFactory(
            KafkaProperties kafkaProperties,
            @Value("${kafka.schema-registry.url}") String schemaRegistryUrl) {
        Map<String, Object> props = kafkaProperties.buildProducerProperties();
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new KafkaProtobufSerializer());
    }

    @Bean
    public KafkaTemplate<String, Object> protobufKafkaTemplate(
            ProducerFactory<String, Object> protobufKafkaProducerFactory) {
        return new KafkaTemplate<>(protobufKafkaProducerFactory);
    }

    @Bean
    public ConsumerFactory<String, TaskCreationRequestedEvent> taskCreationRequestedConsumerFactory(
            KafkaProperties kafkaProperties,
            @Value("${kafka.schema-registry.url}") String schemaRegistryUrl) {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties();
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, KafkaProtobufDeserializer.class.getName());
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        props.put(KafkaProtobufDeserializerConfig.SPECIFIC_PROTOBUF_VALUE_TYPE,
                TaskCreationRequestedEvent.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TaskCreationRequestedEvent>
            taskCreationRequestedContainerFactory(
                    ConsumerFactory<String, TaskCreationRequestedEvent> taskCreationRequestedConsumerFactory,
                    ErrorTopicPublisher errorTopicPublisher,
                    KafkaRetryProperties kafkaRetryProperties) {
        ConcurrentKafkaListenerContainerFactory<String, TaskCreationRequestedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(taskCreationRequestedConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(protobufListenerErrorHandler(errorTopicPublisher, kafkaRetryProperties));
        return factory;
    }

    DefaultErrorHandler protobufListenerErrorHandler(ErrorTopicPublisher errorTopicPublisher,
                                                     KafkaRetryProperties retryProperties) {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, exception) -> {
                    if (isTransientFailure(exception)) {
                        errorTopicPublisher.publish(record, null, "TECHNICAL_FAILURE",
                                "Schema registry unavailable: " + rootCauseMessage(exception), List.of(),
                                retryProperties.getMaxAttempts(), null);
                    } else {
                        errorTopicPublisher.publish(record, null, "VALIDATION_FAILURE",
                                "Malformed protobuf payload: " + rootCauseMessage(exception), List.of(), 0, null);
                    }
                },
                transientFailureBackOff(retryProperties));
        handler.removeClassification(DeserializationException.class);
        handler.setBackOffFunction((record, exception) -> isTransientFailure(exception)
                ? null
                : new FixedBackOff(0L, 0L));
        return handler;
    }

    // Same attempts/backoff as EventProcessingTemplate's retries; max-attempts counts the first try.
    private static ExponentialBackOffWithMaxRetries transientFailureBackOff(KafkaRetryProperties retryProperties) {
        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(retryProperties.getMaxAttempts() - 1);
        backOff.setInitialInterval(retryProperties.getInitialBackoffMs());
        backOff.setMultiplier(retryProperties.getMultiplier());
        backOff.setMaxInterval(retryProperties.getMaxBackoffMs());
        return backOff;
    }

    private static boolean isTransientFailure(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof IOException) {
                return true;
            }
            if (current instanceof RestClientException restClientException && restClientException.getStatus() >= 500) {
                return true;
            }
        }
        return false;
    }

    private String rootCauseMessage(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    @Bean
    public ConsumerFactory<String, StructuredTaskEvent> structuredTaskConsumerFactory(
            KafkaProperties kafkaProperties,
            @Value("${kafka.schema-registry.url}") String schemaRegistryUrl) {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties();
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, KafkaProtobufDeserializer.class.getName());
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        props.put(KafkaProtobufDeserializerConfig.SPECIFIC_PROTOBUF_VALUE_TYPE,
                StructuredTaskEvent.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, StructuredTaskEvent> structuredTaskContainerFactory(
            ConsumerFactory<String, StructuredTaskEvent> structuredTaskConsumerFactory,
            ErrorTopicPublisher errorTopicPublisher,
            KafkaRetryProperties kafkaRetryProperties) {
        ConcurrentKafkaListenerContainerFactory<String, StructuredTaskEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(structuredTaskConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(protobufListenerErrorHandler(errorTopicPublisher, kafkaRetryProperties));
        return factory;
    }

    @Bean
    public ConsumerFactory<String, SchedulingUpdatedEvent> schedulingUpdatedConsumerFactory(
            KafkaProperties kafkaProperties,
            @Value("${kafka.schema-registry.url}") String schemaRegistryUrl) {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties();
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, KafkaProtobufDeserializer.class.getName());
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        props.put(KafkaProtobufDeserializerConfig.SPECIFIC_PROTOBUF_VALUE_TYPE,
                SchedulingUpdatedEvent.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, SchedulingUpdatedEvent> schedulingUpdatedContainerFactory(
            ConsumerFactory<String, SchedulingUpdatedEvent> schedulingUpdatedConsumerFactory,
            ErrorTopicPublisher errorTopicPublisher,
            KafkaRetryProperties kafkaRetryProperties) {
        ConcurrentKafkaListenerContainerFactory<String, SchedulingUpdatedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(schedulingUpdatedConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(protobufListenerErrorHandler(errorTopicPublisher, kafkaRetryProperties));
        return factory;
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService retryScheduler() {
        return Executors.newScheduledThreadPool(2);
    }

    @Bean
    public RetryTemplate kafkaRetryTemplate(KafkaRetryProperties properties) {
        RetryTemplate retryTemplate = new RetryTemplate();

        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(properties.getInitialBackoffMs());
        backOffPolicy.setMultiplier(properties.getMultiplier());
        backOffPolicy.setMaxInterval(properties.getMaxBackoffMs());
        retryTemplate.setBackOffPolicy(backOffPolicy);

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
                properties.getMaxAttempts(),
                Map.of(TransientProcessingException.class, true),
                true);
        retryTemplate.setRetryPolicy(retryPolicy);

        return retryTemplate;
    }
}
