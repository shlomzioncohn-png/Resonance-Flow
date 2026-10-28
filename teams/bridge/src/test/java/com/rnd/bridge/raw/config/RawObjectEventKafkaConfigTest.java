package com.rnd.bridge.raw.config;

import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
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
 * Mirrors BridgeTaskKafkaConfigTest: proves rawObjectEventProducerFactory starts from
 * {@link KafkaProperties#buildProducerProperties(org.springframework.boot.ssl.SslBundles)}, so
 * spring.kafka.properties.* (SASL/security) reaches this producer too - while the Protobuf
 * overrides (serializer, schema registry) still win over whatever spring.kafka.producer.*
 * would otherwise supply.
 */
class RawObjectEventKafkaConfigTest {

    @Test
    void producerFactoryAppliesSaslPropertiesAndKeepsProtobufOverrides() {
        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.getProperties().put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT");
        kafkaProperties.getProperties().put(SaslConfigs.SASL_MECHANISM, "PLAIN");
        kafkaProperties.getProducer().setValueSerializer(JsonSerializer.class);

        ProducerFactory<String, Object> producerFactory = new RawObjectEventKafkaConfig().rawObjectEventProducerFactory(
                kafkaProperties, new DefaultSslBundleRegistry(), "http://localhost:8081/apis/ccompat/v6");

        Map<String, Object> props = producerFactory.getConfigurationProperties();

        assertEquals("SASL_PLAINTEXT", props.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals("PLAIN", props.get(SaslConfigs.SASL_MECHANISM));
        assertEquals(KafkaProtobufSerializer.class, props.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
        assertEquals(false, props.get("auto.register.schemas"));
        assertEquals(true, props.get("use.latest.version"));
        assertEquals("http://localhost:8081/apis/ccompat/v6", props.get("schema.registry.url"));
    }
}
