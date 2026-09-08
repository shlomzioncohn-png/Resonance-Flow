package com.rnd.relay.event;

import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import java.util.HashMap;
import java.util.Map;

// application.yml's spring.kafka.consumer.* properties already pin the auto-configured listener
// factory's protobuf type to TaskEvent, so TaskNotification needs its own factory rather than
// sharing it.
@Configuration
public class NotificationKafkaConfig {

    @Bean
    public ConsumerFactory<String, TaskNotification> taskNotificationConsumerFactory(
            KafkaProperties kafkaProperties, MeterRegistry meterRegistry) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, KafkaProtobufDeserializer.class.getName());
        props.put(KafkaProtobufDeserializerConfig.SPECIFIC_PROTOBUF_VALUE_TYPE, TaskNotification.class.getName());
        DefaultKafkaConsumerFactory<String, TaskNotification> factory = new DefaultKafkaConsumerFactory<>(props);
        // design doc §09 "Kafka consumer lag per partition" — Spring Kafka's own Micrometer binder
        // reports it (kafka.consumer.fetch.manager.records.lag / -max) without hand-rolled polling
        // of end offsets. The TaskEvent listener factory is Spring Boot auto-configured and gets
        // this automatically once Actuator/Micrometer are on the classpath; this factory is custom
        // (see class comment above) so it needs the listener attached explicitly.
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TaskNotification> taskNotificationListenerContainerFactory(
            @Qualifier("taskNotificationConsumerFactory") ConsumerFactory<String, TaskNotification> taskNotificationConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, TaskNotification> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(taskNotificationConsumerFactory);
        return factory;
    }

    // A second, separate factory for KafkaNotificationReplayService's one-off manual-seek reads
    // (design doc §06 pitfall 1, large-gap fallback). No group.id and auto-commit disabled: this
    // consumer only ever assign()s a single partition and reads it on demand — it must never join a
    // consumer group or commit offsets, since that's the live @KafkaListener consumer's job alone.
    @Bean
    public ConsumerFactory<String, TaskNotification> notificationReplayConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, KafkaProtobufDeserializer.class.getName());
        props.put(KafkaProtobufDeserializerConfig.SPECIFIC_PROTOBUF_VALUE_TYPE, TaskNotification.class.getName());
        props.remove(ConsumerConfig.GROUP_ID_CONFIG);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }
}
