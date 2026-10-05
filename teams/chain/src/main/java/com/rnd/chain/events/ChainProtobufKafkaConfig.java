package com.rnd.chain.events;

import com.rnd.inbox.event.TaskCreatedEvent;
import com.rnd.inbox.event.TaskUpdatedEvent;
import com.rnd.mind.event.DependencyProposedEvent;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializerConfig;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializerConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
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
 *
 * Also provides a matching Protobuf-capable KafkaTemplate per message type, for
 * {@code @RetryableTopic}'s own retry/DLT publishing (see each handler's
 * {@code @RetryableTopic(kafkaTemplate = "...")}). Without an explicit template,
 * {@code @RetryableTopic} falls back to the application's single default KafkaTemplate&lt;String,
 * String&gt; (configured for KafkaEventPublisher's flat-JSON publishing) - trying to serialize a
 * typed Protobuf message (e.g. TaskCreatedEvent) through a StringSerializer throws a
 * SerializationException, which itself then fails to publish to the DLT, which retries
 * indefinitely: a message that should go straight to `.errors` instead never arrives there at
 * all. Caught by DependencyProposedEventHandlerIntegrationTest's malformed-event test - a real,
 * production-impacting bug this Protobuf-typed template fixes, not just a test artifact.
 *
 * PR #467 review follow-up: that same Protobuf-typed template then broke the OTHER direction -
 * a genuine deserialization failure (corrupt/non-Protobuf bytes, not a well-formed message with
 * an invalid field) has no decoded object at all, only the original raw bytes.
 * DeadLetterPublishingRecoverer republishes those raw {@code byte[]} through this same template,
 * and {@code KafkaProtobufSerializer} can only serialize a {@code Message}, so the DLT publish
 * itself threw a ClassCastException - the publish failure then retried the recovery indefinitely,
 * permanently stuck on that one poisoned offset (confirmed: subsequent valid records on the same
 * topic were never consumed either). {@link PassthroughOrProtobufSerializer} is the fix: pass
 * {@code byte[]} straight through unchanged (it's already the bytes that need to end up on
 * {@code .errors}), delegate to the real Protobuf serializer for everything else.
 */
@Configuration
public class ChainProtobufKafkaConfig {

    private final KafkaProperties kafkaProperties;

    @Value("${chain.kafka.schema-registry-url}")
    private String schemaRegistryUrl;

    public ChainProtobufKafkaConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    private Map<String, Object> consumerProps(Class<?> specificProtobufType) {
        // Start from Spring Boot's Kafka properties so spring.kafka.properties (SASL/TLS) reaches every factory
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, KafkaProtobufDeserializer.class);
        props.put(KafkaProtobufDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        props.put(KafkaProtobufDeserializerConfig.SPECIFIC_PROTOBUF_VALUE_TYPE, specificProtobufType);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Offsets commit only after the listener (or the retry/DLT hand-off) finishes, see listenerContainerFactory
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return props;
    }

    private static <T> ConcurrentKafkaListenerContainerFactory<String, T> listenerContainerFactory(
            ConsumerFactory<String, T> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, T> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    private <T> KafkaTemplate<String, T> protobufTemplate() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, PassthroughOrProtobufSerializer.class);
        props.put(KafkaProtobufSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        ProducerFactory<String, T> producerFactory = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * See the class Javadoc's "PR #467 review follow-up" note. Registered via
     * {@code VALUE_SERIALIZER_CLASS_CONFIG} like any other serializer - Kafka's producer config
     * instantiates it by reflection (no-arg constructor) and calls {@code configure(...)}, same as
     * it would for {@link KafkaProtobufSerializer} directly.
     */
    public static final class PassthroughOrProtobufSerializer implements Serializer<Object> {

        private final KafkaProtobufSerializer<com.google.protobuf.Message> delegate = new KafkaProtobufSerializer<>();

        @Override
        public void configure(Map<String, ?> configs, boolean isKey) {
            delegate.configure(configs, isKey);
        }

        @Override
        public byte[] serialize(String topic, Object data) {
            if (data instanceof byte[] raw) {
                // The original failed record's raw bytes, handed to us by
                // DeadLetterPublishingRecoverer recovering a deserialization failure - there is
                // no decoded Message to re-encode, and these bytes are already exactly what
                // belongs on .errors.
                return raw;
            }
            return delegate.serialize(topic, (com.google.protobuf.Message) data);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    @Bean
    public ConsumerFactory<String, TaskCreatedEvent> taskCreatedEventConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(consumerProps(TaskCreatedEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TaskCreatedEvent> taskCreatedEventKafkaListenerContainerFactory(
            ConsumerFactory<String, TaskCreatedEvent> taskCreatedEventConsumerFactory) {
        return listenerContainerFactory(taskCreatedEventConsumerFactory);
    }

    @Bean
    public KafkaTemplate<String, TaskCreatedEvent> taskCreatedEventKafkaTemplate() {
        return protobufTemplate();
    }

    @Bean
    public ConsumerFactory<String, TaskUpdatedEvent> taskUpdatedEventConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(consumerProps(TaskUpdatedEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TaskUpdatedEvent> taskUpdatedEventKafkaListenerContainerFactory(
            ConsumerFactory<String, TaskUpdatedEvent> taskUpdatedEventConsumerFactory) {
        return listenerContainerFactory(taskUpdatedEventConsumerFactory);
    }

    @Bean
    public KafkaTemplate<String, TaskUpdatedEvent> taskUpdatedEventKafkaTemplate() {
        return protobufTemplate();
    }

    @Bean
    public ConsumerFactory<String, DependencyProposedEvent> dependencyProposedEventConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(consumerProps(DependencyProposedEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DependencyProposedEvent> dependencyProposedEventKafkaListenerContainerFactory(
            ConsumerFactory<String, DependencyProposedEvent> dependencyProposedEventConsumerFactory) {
        return listenerContainerFactory(dependencyProposedEventConsumerFactory);
    }

    @Bean
    public KafkaTemplate<String, DependencyProposedEvent> dependencyProposedEventKafkaTemplate() {
        return protobufTemplate();
    }

    /**
     * Plain String/String KafkaTemplate for KafkaEventPublisher's flat-JSON publishing
     * (task.scheduling.updated). Spring Boot's KafkaAutoConfiguration would normally supply this
     * automatically from spring.kafka.producer.* - but its {@code @ConditionalOnMissingBean
     * (KafkaTemplate.class)} guard checks the raw type only (generics are erased at runtime), so
     * once this class defines any other KafkaTemplate bean (the Protobuf-typed ones above),
     * autoconfiguration backs off from creating this one too. Defined explicitly here so
     * KafkaEventPublisher keeps getting exactly what it did before - same serializers as
     * spring.kafka.producer.* in application.yml.
     */
    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }
}
