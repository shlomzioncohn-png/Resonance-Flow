package com.rnd.inbox.shared.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.inbox.shared.messaging.kafka.TransientProcessingException;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
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
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
@EnableConfigurationProperties(KafkaRetryProperties.class)
public class KafkaConfig {

    @Bean
    public ConsumerFactory<String, String> inboxKafkaConsumerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaConsumerFactory<>(kafkaProperties.buildConsumerProperties());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> inboxKafkaConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(inboxKafkaConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    @Bean
    public ProducerFactory<String, String> inboxKafkaProducerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties());
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> inboxKafkaProducerFactory) {
        return new KafkaTemplate<>(inboxKafkaProducerFactory);
    }

    @Bean
    public ProducerFactory<String, Object> jsonKafkaProducerFactory(KafkaProperties kafkaProperties,
                                                                    ObjectMapper objectMapper) {
        JsonSerializer<Object> valueSerializer = new JsonSerializer<>(objectMapper);
        valueSerializer.setAddTypeInfo(false);
        return new DefaultKafkaProducerFactory<>(
                kafkaProperties.buildProducerProperties(), new StringSerializer(), valueSerializer);
    }

    @Bean
    public KafkaTemplate<String, Object> jsonKafkaTemplate(ProducerFactory<String, Object> jsonKafkaProducerFactory) {
        return new KafkaTemplate<>(jsonKafkaProducerFactory);
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
