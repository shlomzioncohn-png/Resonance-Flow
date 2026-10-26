package com.rnd.bridge.publish.kafka;

import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializerConfig;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves issue #567's fix: {@code bridgeTaskEventProducerFactory} starts from
 * {@link KafkaProperties#buildProducerProperties(org.springframework.boot.ssl.SslBundles)}, so
 * {@code spring.kafka.properties.*} (SASL/security) reaches this producer - while the Protobuf
 * overrides (serializer, idempotence, schema registry) still win over whatever
 * {@code spring.kafka.producer.*} would otherwise supply.
 */
class BridgeTaskKafkaConfigTest {

    @Test
    void producerFactoryAppliesSaslPropertiesAndKeepsProtobufOverrides() {
        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.getProperties().put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT");
        kafkaProperties.getProperties().put(SaslConfigs.SASL_MECHANISM, "PLAIN");
        kafkaProperties.getProducer().setValueSerializer(JsonSerializer.class);

        ProducerFactory<String, ?> producerFactory = new BridgeTaskKafkaConfig().bridgeTaskEventProducerFactory(
                kafkaProperties, new DefaultSslBundleRegistry(), "http://localhost:8081/apis/ccompat/v6");

        Map<String, Object> props = producerFactory.getConfigurationProperties();

        assertEquals("SASL_PLAINTEXT", props.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals("PLAIN", props.get(SaslConfigs.SASL_MECHANISM));
        assertEquals(KafkaProtobufSerializer.class, props.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
        assertEquals(false, props.get(KafkaProtobufSerializerConfig.AUTO_REGISTER_SCHEMAS));
        assertEquals(true, props.get(KafkaProtobufSerializerConfig.USE_LATEST_VERSION));
        assertEquals(true, props.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
    }
}
