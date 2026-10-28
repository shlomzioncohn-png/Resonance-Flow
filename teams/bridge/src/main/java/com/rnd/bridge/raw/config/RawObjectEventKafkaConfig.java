package com.rnd.bridge.raw.config;

import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * A dedicated Protobuf producer for RawObjectEvent (topic bridge.raw.landed), the same shape
 * as BridgeTaskKafkaConfig.bridgeTaskEventKafkaTemplate: starts from
 * {@link KafkaProperties#buildProducerProperties(SslBundles)} so spring.kafka.properties.*
 * (SASL/security) reaches this producer too, same fix as BridgeTaskKafkaConfig's own (issue
 * #567) - not the shared spring.kafka.producer.* block in application.yml, which stays
 * JsonSerializer for GitHub/CRM/JIRA and (for now - see MediaEventPublisher's own javadoc)
 * Media. schema.registry.url/auto.register.schemas/use.latest.version follow the same
 * convention as Inbox and Relay's KafkaProtobufSerializer wiring - see
 * teams/relay/docs/kafka-producer-consumer-guide.md: schemas are registered centrally by the
 * schema-sync-hook, never by a producer.
 */
@Configuration
class RawObjectEventKafkaConfig {

    @Bean
    ProducerFactory<String, Object> rawObjectEventProducerFactory(
            KafkaProperties kafkaProperties,
            SslBundles sslBundles,
            @Value("${spring.kafka.properties.schema.registry.url}") String schemaRegistryUrl) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(sslBundles));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaProtobufSerializer.class);
        props.put("schema.registry.url", schemaRegistryUrl);
        props.put("auto.register.schemas", false);
        props.put("use.latest.version", true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    KafkaTemplate<String, Object> rawObjectEventKafkaTemplate(
            ProducerFactory<String, Object> rawObjectEventProducerFactory) {
        return new KafkaTemplate<>(rawObjectEventProducerFactory);
    }
}
