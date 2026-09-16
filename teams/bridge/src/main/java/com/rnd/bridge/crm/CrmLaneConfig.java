package com.rnd.bridge.crm;

import com.rnd.bridge.crm.mapper.DealToTaskMapper;
import com.rnd.bridge.publish.LoggingPayloadPublisher;
import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.TaskEnricher;
import com.rnd.bridge.publish.kafka.KafkaPayloadPublisher;
import com.rnd.bridge.task.BridgeTaskEventDto;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.model.Task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Bean wiring for {@link CrmLane}'s collaborators that intentionally carry
 * no Spring annotations of their own - {@link DealToTaskMapper} and
 * {@link TaskValidator} are plain, framework-free classes shared or shareable
 * across lanes - plus the publisher, which is the logging stand-in unless
 * the {@code kafka} profile is active, mirroring how the {@code hubspot}
 * profile overrides {@code JsonDealRepository} elsewhere in this package.
 */
@Configuration
class CrmLaneConfig {

    @Bean
    DealToTaskMapper dealToTaskMapper(@Value("${bridge.crm.organization-id:local-crm-org}") String organizationId,
                                      EnrichmentProperties enrichmentProperties,
                                      TaskEnricher taskEnricher) {
        return new DealToTaskMapper(organizationId, enrichmentProperties, taskEnricher);
    }

    @Bean
    TaskValidator taskValidator() {
        return new TaskValidator();
    }

    @Bean
    LoggingPayloadPublisher<Task> taskPublisher() {
        return new LoggingPayloadPublisher<>();
    }

    @Bean
    @Profile("kafka")
    @Primary
    KafkaPayloadPublisher kafkaTaskPublisher(
            KafkaTemplate<String, BridgeTaskEventDto> kafkaTemplate,
            @Value("${bridge.structured-tasks-topic:bridge.structured.tasks}") String topic) {
        return new KafkaPayloadPublisher(kafkaTemplate, topic);
    }
}
