package com.rnd.bridge.crm;

import com.rnd.bridge.crm.mapper.DealToTaskMapper;
import com.rnd.bridge.publish.LoggingPayloadPublisher;
import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.TaskEnricher;
import com.rnd.bridge.publish.kafka.KafkaPayloadPublisher;
import com.rnd.bridge.task.BridgeTaskEventDto;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.model.Task;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Bean wiring for {@link CrmLane}'s collaborators that intentionally carry
 * no Spring annotations of their own - {@link DealToTaskMapper} and
 * {@link CrmTaskValidator} are plain, framework-free classes shared or shareable
 * across lanes - plus the publisher, which is the logging stand-in unless
 * the {@code kafka} profile is active, mirroring how the {@code hubspot}
 * profile overrides {@code JsonDealRepository} elsewhere in this package.
 */
@Configuration
class CrmLaneConfig {

    @Bean
    DealToTaskMapper dealToTaskMapper(
            @Value("${bridge.crm.organization-id:" + CrmTaskValidator.LOCAL_PLACEHOLDER_TENANT_ID + "}")
            String organizationId,
            Environment environment,
            EnrichmentProperties enrichmentProperties,
            TaskEnricher taskEnricher) {
        // CrmTaskValidator.LOCAL_PLACEHOLDER_TENANT_ID only makes sense without the kafka profile
        // (fixture data, LoggingPayloadPublisher - nothing leaves the process). Once kafka is
        // active, a missing/malformed/still-the-placeholder tenant id means every published
        // message is silently rejected by Inbox's own tenant validation and routed to an error
        // topic nobody consumes (finding F5 in the CRM-lane audit) - fail here, at startup,
        // instead of discovering that later.
        if (environment.matchesProfiles("kafka")) {
            requireValidTenantIdForKafka(organizationId);
        }
        return new DealToTaskMapper(organizationId, enrichmentProperties, taskEnricher);
    }

    private static void requireValidTenantIdForKafka(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalStateException(
                    "CRM_ORGANIZATION_ID is required when the 'kafka' profile is active - "
                            + "set it to the tenant UUID Inbox issued for this environment");
        }
        if (!CrmTaskValidator.isValidTenantId(organizationId)) {
            throw new IllegalStateException(
                    "CRM_ORGANIZATION_ID = '" + organizationId + "' is not a valid UUID, "
                            + "required when the 'kafka' profile is active");
        }
        if (CrmTaskValidator.LOCAL_PLACEHOLDER_TENANT_ID.equals(organizationId)) {
            throw new IllegalStateException(
                    "CRM_ORGANIZATION_ID is still the local-only placeholder ("
                            + CrmTaskValidator.LOCAL_PLACEHOLDER_TENANT_ID + ") - the 'kafka' profile "
                            + "needs a real tenant UUID Inbox issued for this environment, not this one");
        }
    }

    @Bean
    TaskValidator taskValidator() {
        return new TaskValidator();
    }

    @Bean
    CrmTaskValidator crmTaskValidator(TaskValidator taskValidator) {
        return new CrmTaskValidator(taskValidator);
    }

    @Bean
    LoggingPayloadPublisher<Task> taskPublisher() {
        return new LoggingPayloadPublisher<>();
    }

    @Bean
    @Profile("kafka")
    @Primary
    KafkaPayloadPublisher kafkaTaskPublisher(
            @Qualifier("bridgeTaskEventKafkaTemplate") KafkaTemplate<String, BridgeTaskEventDto> kafkaTemplate,
            @Value("${bridge.structured-tasks-topic:bridge.structured.tasks}") String topic) {
        return new KafkaPayloadPublisher(kafkaTemplate, topic);
    }
}
