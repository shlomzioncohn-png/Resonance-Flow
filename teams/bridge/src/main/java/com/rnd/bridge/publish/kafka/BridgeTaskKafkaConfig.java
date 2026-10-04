package com.rnd.bridge.publish.kafka;

import com.rnd.bridge.task.BridgeTaskEventDto;
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
 * A dedicated producer for {@link BridgeTaskEventDto}, plus an explicit
 * replacement for the general-purpose {@code KafkaTemplate} every other lane
 * (GitHub, Media/Pointer) needs.
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
 * {@code spring.kafka.producer.*}).
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
 * {@code KafkaTemplate<String, BridgeTaskEventDto>} exists alongside a
 * wildcard bean, the wildcard bean is - by the same "matches anything" logic
 * - *also* a candidate for the exact-type request, which is ambiguous
 * without a name to break the tie:
 * {@code @Qualifier("kafkaTemplate")} on {@code GitHubKafkaProducer},
 * {@code MediaEventPublisher} and {@code PointerEventPublisher};
 * {@code @Qualifier("bridgeTaskEventKafkaTemplate")} on
 * {@code CrmLaneConfig.kafkaTaskPublisher} and {@code JiraKafkaProducer}.
 * This version is confirmed against a real context load
 * ({@code BridgeApplicationTests}) plus the full {@code teams/bridge} suite,
 * not just reasoned through.
 * <p>
 * This is the seam a future CRM/JIRA-to-Protobuf migration needs: changing
 * {@link #bridgeTaskEventKafkaTemplate}'s {@code value-serializer} affects
 * only the CRM/JIRA path, never {@link #kafkaTemplate}'s. Still
 * {@link JsonSerializer} for now on both beans - this commit changes wiring
 * only, not wire format. There is no reviewed {@code .proto} for
 * {@code bridge.structured.tasks} yet, no {@code schema-subjects.tsv} row
 * for it, and Inbox's consumer still reads JSON - switching the serializer
 * now would re-break the pipe the tenantId fix just repaired. {@code
 * enable.idempotence} is turned on for {@link #bridgeTaskEventKafkaTemplate}
 * only ({@link #kafkaTemplate} mirrors the old shared default's config
 * exactly, including its lack of idempotence - GitHub/Media weren't asked
 * for that change): {@code retries: 3} without idempotence risks two
 * retried batches landing out of order within a partition.
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
    ProducerFactory<String, BridgeTaskEventDto> bridgeTaskEventProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    KafkaTemplate<String, BridgeTaskEventDto> bridgeTaskEventKafkaTemplate(
            ProducerFactory<String, BridgeTaskEventDto> bridgeTaskEventProducerFactory) {
        return new KafkaTemplate<>(bridgeTaskEventProducerFactory);
    }
}
