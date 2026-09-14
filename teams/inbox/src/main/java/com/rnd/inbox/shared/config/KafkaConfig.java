package com.rnd.inbox.shared.config;

import com.rnd.inbox.shared.messaging.kafka.TransientProcessingException;
import org.apache.kafka.common.serialization.StringSerializer;
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

    // Explicit StringSerializer instances, not kafkaProperties.buildProducerProperties() alone -
    // spring.kafka.producer.value-serializer is KafkaProtobufSerializer (for the Protobuf
    // producer below), and passing serializer instances is what overrides that shared config
    // for this plain String producer (used by ErrorTopicPublisher).
    @Bean
    public ProducerFactory<String, String> inboxKafkaProducerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaProducerFactory<>(
                kafkaProperties.buildProducerProperties(), new StringSerializer(), new StringSerializer());
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> inboxKafkaProducerFactory) {
        return new KafkaTemplate<>(inboxKafkaProducerFactory);
    }

    /**
     * key/value serializers and schema.registry.url are driven entirely by
     * spring.kafka.producer.* / spring.kafka.properties.* in application.yml - see
     * teams/relay/docs/kafka-producer-consumer-guide.md. Kafka instantiates
     * KafkaProtobufSerializer from those properties via reflection; no serializer instance is
     * built by hand here.
     */
    @Bean
    public ProducerFactory<String, Object> protobufKafkaProducerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties());
    }

    @Bean
    public KafkaTemplate<String, Object> protobufKafkaTemplate(ProducerFactory<String, Object> protobufKafkaProducerFactory) {
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
