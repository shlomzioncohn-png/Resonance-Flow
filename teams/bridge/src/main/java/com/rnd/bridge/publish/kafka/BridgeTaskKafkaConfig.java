package com.rnd.bridge.publish.kafka;

import com.rnd.inbox.event.StructuredTaskEvent;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializerConfig;
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
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * A dedicated producer for {@code bridge.structured.tasks} ({@link StructuredTaskEvent}, Protobuf
 * - see {@link com.rnd.bridge.task.StructuredTaskEventMapper}), plus an explicit replacement for
 * the general-purpose {@code KafkaTemplate} every other lane (GitHub, Media/Pointer) needs.
 * <p>
 * <b>Why the general-purpose bean has to be redeclared here too.</b> Spring
 * Boot's {@code KafkaAutoConfiguration} only creates its own default
 * {@code KafkaTemplate} when {@code @ConditionalOnMissingBean(KafkaTemplate.class)}
 * is satisfied - and that check matches on the raw {@code KafkaTemplate}
 * class, ignoring generic type parameters entirely. Declaring
 * {@link #bridgeTaskEventKafkaTemplate} alone makes Spring Boot skip its own
 * default bean altogether, not just add a second candidate next to it -
 * which broke {@code GitHubKafkaProducer}, {@code MediaEventPublisher} and
 * {@code PointerEventPublisher} with {@code NoSuchBeanDefinitionException}
 * at context startup ({@code BridgeApplicationTests} caught it).
 * {@link #kafkaTemplate} rebuilds exactly what {@code KafkaAutoConfiguration}
 * would have, from the same {@link KafkaProperties} (i.e.
 * {@code spring.kafka.producer.*}). It stays on {@link JsonSerializer} and is
 * otherwise untouched by the Protobuf switch below - GitHub/Media/Pointer
 * have no Protobuf schema of their own yet.
 * <p>
 * <b>{@code KafkaTemplate<?, ?>}, not {@code KafkaTemplate<Object, Object>} -
 * verified empirically, not assumed, after two wrong guesses.</b> Attempt 1
 * declared only {@link #bridgeTaskEventKafkaTemplate} (no general-purpose
 * bean at all - broke GitHub/Media, see above). Attempt 2 added a
 * general-purpose bean back, but typed its return as
 * {@code KafkaTemplate<Object, Object>} and assumed Spring would treat that
 * as compatible with any narrower request the way the real autoconfigured
 * bean is - still {@code NoSuchBeanDefinitionException} for
 * {@code KafkaTemplate<String, Task>} even with an explicit
 * {@code @Qualifier("kafkaTemplate")} naming this exact bean, because a
 * concrete {@code Object, Object} parameterization is exactly that -
 * concrete - and Spring's generics-aware matching does not treat it as a
 * supertype of {@code KafkaTemplate<String, Task>}. Spring Boot's real
 * {@code KafkaAutoConfiguration.kafkaTemplate(...)} method declares its
 * return type as the actual Java wildcard {@code KafkaTemplate<?, ?>}, which
 * Spring's {@code ResolvableType} machinery treats as unbound and therefore
 * assignable to any narrower generic request - that, not anything about
 * {@code Object} specifically, is what let one autoconfigured bean satisfy
 * four differently-typed lanes before this class existed. {@link
 * #kafkaTemplate} now uses that same wildcard return type.
 * <p>
 * Every consumer still needs an explicit {@code @Qualifier}, because once
 * {@link #bridgeTaskEventKafkaTemplate}'s fully-resolved
 * {@code KafkaTemplate<String, StructuredTaskEvent>} exists alongside a
 * wildcard bean, the wildcard bean is - by the same "matches anything" logic
 * - *also* a candidate for the exact-type request, which is ambiguous
 * without a name to break the tie:
 * {@code @Qualifier("kafkaTemplate")} on {@code GitHubKafkaProducer},
 * {@code MediaEventPublisher} and {@code PointerEventPublisher};
 * {@code @Qualifier("bridgeTaskEventKafkaTemplate")} on
 * {@code CrmLaneConfig.kafkaTaskPublisher} and {@code JiraKafkaProducer}.
 * <p>
 * <b>Protobuf switch (finding F3 in the CRM-lane audit / issue #567).</b>
 * {@link #bridgeTaskEventKafkaTemplate} now sends {@link StructuredTaskEvent} via Confluent's
 * {@link KafkaProtobufSerializer}, matching Inbox's {@code KafkaProtobufDeserializer} on the
 * consumer side - the two sides no longer disagree on wire format. {@code
 * schema.registry.url} is read from {@code bridge.kafka.schema-registry.url} (no hardcoded
 * default beyond the local-dev fallback in {@code application.yml}).
 * {@code auto.register.schemas} is explicitly {@code false}: registering the schema is Relay's
 * job (adding the {@code bridge.structured.tasks-value} row to {@code schema-subjects.tsv}, see
 * issue #567), not something this producer should do as a side effect of its first publish -
 * letting a producer auto-register would mean the schema Inbox ends up validating against was
 * whatever Bridge happened to send first, not a reviewed contract. {@code enable.idempotence}
 * stays on for this bean only, same as before the Protobuf switch: {@code retries: 3} without
 * idempotence risks two retried batches landing out of order within a partition.
 */
@Configuration
class BridgeTaskKafkaConfig {

    @Bean
    ProducerFactory<Object, Object> kafkaProducerFactory(KafkaProperties kafkaProperties, SslBundles sslBundles) {
        return new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties(sslBundles));
    }

    @Bean
    KafkaTemplate<?, ?> kafkaTemplate(ProducerFactory<Object, Object> kafkaProducerFactory) {
        return new KafkaTemplate<>(kafkaProducerFactory);
    }

    @Bean
    ProducerFactory<String, StructuredTaskEvent> bridgeTaskEventProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${bridge.kafka.schema-registry.url}") String schemaRegistryUrl) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaProtobufSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        // Relay registers the schema (schema-subjects.tsv) - this producer must never register one
        // as a side effect of publishing, or Inbox would end up validating against whatever Bridge
        // happened to send first instead of a reviewed contract. See issue #567.
        props.put(KafkaProtobufSerializerConfig.AUTO_REGISTER_SCHEMAS, false);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    KafkaTemplate<String, StructuredTaskEvent> bridgeTaskEventKafkaTemplate(
            ProducerFactory<String, StructuredTaskEvent> bridgeTaskEventProducerFactory) {
        return new KafkaTemplate<>(bridgeTaskEventProducerFactory);
    }
}
