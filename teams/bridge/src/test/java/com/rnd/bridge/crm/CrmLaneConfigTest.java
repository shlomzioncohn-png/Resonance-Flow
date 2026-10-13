package com.rnd.bridge.crm;

import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.NoOpTaskEnricher;
import com.rnd.bridge.task.enrichment.TaskEnricher;
import com.rnd.inbox.event.StructuredTaskEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * F5 (CRM lane): a missing or malformed CRM_ORGANIZATION_ID must fail the application loudly once
 * the {@code kafka} profile is active - every message would otherwise be silently rejected by
 * Inbox's own tenant validation and routed to an error topic nobody consumes. Without
 * {@code kafka}, the {@code local-crm-org} placeholder must keep working, so a purely
 * local/fixture run isn't forced to set a tenant id it has no use for.
 */
class CrmLaneConfigTest {

    @Configuration
    static class TestCollaborators {
        @Bean
        EnrichmentProperties enrichmentProperties() {
            return new EnrichmentProperties();
        }

        @Bean
        TaskEnricher taskEnricher() {
            return new NoOpTaskEnricher();
        }

        // CrmLaneConfig.kafkaTaskPublisher is also @Profile("kafka") and needs this exact
        // qualifier - stubbed so activating "kafka" here doesn't also require BridgeTaskKafkaConfig
        // (a different, unrelated @Configuration this test has nothing to do with).
        @SuppressWarnings("unchecked")
        @Bean(name = "bridgeTaskEventKafkaTemplate")
        KafkaTemplate<String, StructuredTaskEvent> bridgeTaskEventKafkaTemplate() {
            return mock(KafkaTemplate.class);
        }
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CrmLaneConfig.class, TestCollaborators.class);

    @Test
    void startsWithTheLocalPlaceholderWhenKafkaProfileIsNotActive() {
        contextRunner.run(context -> assertNull(context.getStartupFailure()));
    }

    @Test
    void startsWithAnExplicitValidTenantIdWhenKafkaProfileIsActive() {
        contextRunner
                .withPropertyValues("bridge.crm.organization-id=11111111-1111-1111-1111-111111111111")
                .withInitializer(context -> context.getEnvironment().addActiveProfile("kafka"))
                .run(context -> assertNull(context.getStartupFailure()));
    }

    @Test
    void failsToStartWithTheLocalPlaceholderWhenKafkaProfileIsActive() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().addActiveProfile("kafka"))
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void failsToStartWhenThePlaceholderIsExplicitlySetWhileKafkaProfileIsActive() {
        // Distinct from the test above: here the property is explicitly present (not just falling
        // through to the default), proving the rejection is by VALUE, not merely "was this
        // overridden at all" - otherwise someone could copy the placeholder into a real properties
        // file and still pass.
        contextRunner
                .withPropertyValues("bridge.crm.organization-id=" + CrmTaskValidator.LOCAL_PLACEHOLDER_TENANT_ID)
                .withInitializer(context -> context.getEnvironment().addActiveProfile("kafka"))
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void failsToStartWithAMalformedTenantIdWhenKafkaProfileIsActive() {
        contextRunner
                .withPropertyValues("bridge.crm.organization-id=not-a-uuid")
                .withInitializer(context -> context.getEnvironment().addActiveProfile("kafka"))
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void failsToStartWhenBothHubspotAndSalesforceProfilesAreActive() {
        contextRunner
                .withInitializer(context -> {
                    context.getEnvironment().addActiveProfile("hubspot");
                    context.getEnvironment().addActiveProfile("salesforce");
                })
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("hubspot")
                        .hasMessageContaining("salesforce"));
    }

    @Test
    void startsWithOnlyTheHubspotProfileActive() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().addActiveProfile("hubspot"))
                .run(context -> assertNull(context.getStartupFailure()));
    }

    @Test
    void startsWithOnlyTheSalesforceProfileActive() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().addActiveProfile("salesforce"))
                .run(context -> assertNull(context.getStartupFailure()));
    }
}
