package com.rnd.bridge.crm;

import com.rnd.bridge.crm.mapper.DealToTaskMapper;
import com.rnd.bridge.publish.LoggingPayloadPublisher;
import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.TaskEnricher;
import com.rnd.bridge.publish.kafka.KafkaPayloadPublisher;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.model.Task;
import com.rnd.inbox.event.StructuredTaskEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Bean wiring for {@link CrmLane}'s collaborators (the mapper, validator, and publisher).
 * The {@code kafka} profile requires a real tenant UUID for {@code bridge.crm.organization-id}, not the placeholder.
 */
@Configuration
class CrmLaneConfig {

    @Bean
    DealToTaskMapper dealToTaskMapper(
            @Value("${bridge.crm.organization-id:" + CrmTaskValidator.LOCAL_PLACEHOLDER_TENANT_ID + "}")
            String organizationId,
            Environment environment,
            EnrichmentProperties enrichmentProperties,
            TaskEnricher taskEnricher,
            @Value("${bridge.crm.stalled-after-days:14}")
            int stalledAfterDays) {
        // Under kafka, a missing/placeholder tenant id means every message is silently rejected by Inbox (F5).
        if (environment.matchesProfiles("kafka")) {
            requireValidTenantIdForKafka(organizationId);
        }
        return new DealToTaskMapper(organizationId, enrichmentProperties, taskEnricher, stalledAfterDays);
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
            @Qualifier("bridgeTaskEventKafkaTemplate") KafkaTemplate<String, StructuredTaskEvent> kafkaTemplate,
            @Value("${bridge.structured-tasks-topic:bridge.structured.tasks}") String topic) {
        return new KafkaPayloadPublisher(kafkaTemplate, topic);
    }
}
