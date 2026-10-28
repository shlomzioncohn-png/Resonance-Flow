package com.rnd.bridge.crm.enrichment;

import com.rnd.bridge.ai.LlmClients;
import com.rnd.bridge.ai.LlmException;
import com.rnd.bridge.crm.mapper.DealToTaskMapper;
import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
import com.rnd.bridge.task.enrichment.EnrichmentInput;
import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.NoOpTaskEnricher;
import com.rnd.bridge.task.enrichment.RuleBasedTaskEnricher;
import com.rnd.bridge.task.enrichment.TaskEnricher;
import com.rnd.bridge.task.enrichment.rules.ConfidenceCalculator;
import com.rnd.bridge.task.enrichment.rules.DateExtractor;
import com.rnd.bridge.task.enrichment.rules.UrgencyKeywordScorer;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Lightweight slice context (not the full {@code BridgeApplication}) proving the
 * {@code SourceRoutingTaskEnricher}/{@code CrmEnrichmentConfig} wiring itself, with a
 * fake {@link LlmClients} - never a real Gemini call. The full-application claim
 * ("starts with no Gemini key, enrichment disabled") is exercised for real by the
 * existing {@code BridgeApplicationTests} full context load, which now also boots
 * these same beans via component scan - see that test for the real-{@code LlmClients}
 * case this one deliberately does not re-test.
 */
class CrmEnrichmentSpringContextTest {

    private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";

    private static final String VALID_REPLY = "{\"title\": \"Prepare the proposal\", "
            + "\"description\": \"Revise pricing per the new terms.\", \"effortEstimateMinutes\": 45}";

    @Configuration
    @EnableConfigurationProperties(EnrichmentProperties.class)
    static class PropertiesConfig {
    }

    @Configuration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private ApplicationContextRunner contextRunner(FakeLlmClient fakeClient) {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        CrmEnrichmentConfig.class,
                        SourceRoutingTaskEnricher.class,
                        RuleBasedTaskEnricher.class,
                        UrgencyKeywordScorer.class,
                        DateExtractor.class,
                        ConfidenceCalculator.class,
                        PropertiesConfig.class,
                        ClockConfig.class)
                .withBean(LlmClients.class, () -> new FakeLlmClients(fakeClient))
                // Disabled here - this slice only verifies wiring/routing, not throttling
                // (see CrmDealLlmEnricherTest for the rate-limiter's own behavior).
                .withPropertyValues("bridge.crm.enrichment.min-call-interval=0s");
    }

    private static Deal deal(String description) {
        return Deal.builder()
                .dealId("D-1")
                .title("ETL title")
                .description(description)
                .stage(DealStage.PROPOSAL)
                .closeDate(LocalDate.parse("2026-12-01"))
                .build();
    }

    @Test
    void contextLoads_enrichmentDisabledByDefault() {
        contextRunner(FakeLlmClient.returning(VALID_REPLY)).run(context -> assertNull(context.getStartupFailure()));
    }

    @Test
    void primaryTaskEnricherBean_isSourceRoutingTaskEnricher() {
        contextRunner(FakeLlmClient.returning(VALID_REPLY)).run(context -> {
            TaskEnricher enricher = context.getBean(TaskEnricher.class);
            assertInstanceOf(SourceRoutingTaskEnricher.class, enricher);
        });
    }

    @Test
    void gitHubIssueSource_stillUsesRuleBasedBehavior_whenEnabledForIt() {
        contextRunner(FakeLlmClient.returning(VALID_REPLY))
                .withPropertyValues("llm.enrichment.enabled=true", "llm.enrichment.supported-sources=GITHUB_ISSUE")
                .run(context -> {
                    TaskEnricher enricher = context.getBean(TaskEnricher.class);
                    RuleBasedTaskEnricher ruleBased = context.getBean(RuleBasedTaskEnricher.class);

                    EnrichmentInput input = new EnrichmentInput(SourceType.GITHUB_ISSUE, null,
                            "This is urgent! due 2026-12-31", TaskStatus.NEW, "gh:1", Instant.now(), null, null);

                    assertEquals(ruleBased.enrich(input), enricher.enrich(input));
                });
    }

    @Test
    void crmEnrichmentDisabled_dealToTaskMapperProducesUnmodifiedEtlTask() {
        contextRunner(FakeLlmClient.returning(VALID_REPLY)).run(context -> {
            EnrichmentProperties properties = context.getBean(EnrichmentProperties.class);
            TaskEnricher enricher = context.getBean(TaskEnricher.class);
            DealToTaskMapper mapper = new DealToTaskMapper(TENANT_ID, properties, enricher, 14);

            Task task = mapper.map(deal("Customer asked to revise pricing."), Instant.parse("2026-11-01T00:00:00Z"));

            assertEquals("ETL title", task.title());
            assertEquals("Customer asked to revise pricing.", task.description());
            assertNull(task.effortEstimateMinutes());
        });
    }

    @Test
    void crmEnrichmentEnabled_dealToTaskMapperAppliesEnrichedFields_preservingProtectedFields() {
        contextRunner(FakeLlmClient.returning(VALID_REPLY))
                .withPropertyValues("llm.enrichment.enabled=true", "llm.enrichment.supported-sources=CRM_DEAL")
                .run(context -> {
                    EnrichmentProperties properties = context.getBean(EnrichmentProperties.class);
                    TaskEnricher enricher = context.getBean(TaskEnricher.class);
                    DealToTaskMapper mapper = new DealToTaskMapper(TENANT_ID, properties, enricher, 14);
                    DealToTaskMapper etlOnlyMapper =
                            new DealToTaskMapper(TENANT_ID, new EnrichmentProperties(), new NoOpTaskEnricher(), 14);

                    Deal deal = deal("Customer asked to revise pricing.");
                    Instant asOf = Instant.parse("2026-11-01T00:00:00Z");

                    Task baseline = etlOnlyMapper.map(deal, asOf);
                    Task enriched = mapper.map(deal, asOf);

                    assertEquals("Prepare the proposal", enriched.title());
                    assertEquals(
                            "Revise pricing per the new terms.\n\nOriginal CRM notes:\nCustomer asked to revise pricing.",
                            enriched.description());
                    assertEquals(45, enriched.effortEstimateMinutes());

                    assertEquals(baseline.status(), enriched.status());
                    assertEquals(baseline.urgency(), enriched.urgency());
                    assertEquals(baseline.deadline(), enriched.deadline());
                    assertEquals(baseline.sourceRef(), enriched.sourceRef());
                    assertEquals(baseline.organizationId(), enriched.organizationId());
                    assertEquals(TENANT_ID, enriched.organizationId());
                });
    }

    @Test
    void invalidLlmReply_dealToTaskMapperFallsBackToEtl() {
        contextRunner(FakeLlmClient.returning("not json at all"))
                .withPropertyValues("llm.enrichment.enabled=true", "llm.enrichment.supported-sources=CRM_DEAL")
                .run(context -> {
                    EnrichmentProperties properties = context.getBean(EnrichmentProperties.class);
                    TaskEnricher enricher = context.getBean(TaskEnricher.class);
                    DealToTaskMapper mapper = new DealToTaskMapper(TENANT_ID, properties, enricher, 14);

                    Task task = mapper.map(
                            deal("Customer asked to revise pricing."), Instant.parse("2026-11-01T00:00:00Z"));

                    assertEquals("ETL title", task.title());
                    assertEquals("Customer asked to revise pricing.", task.description());
                    assertNull(task.effortEstimateMinutes());
                });
    }

    @Test
    void llmProviderFailure_dealToTaskMapperFallsBackToEtl() {
        contextRunner(FakeLlmClient.throwing(new LlmException("simulated provider failure", true)))
                .withPropertyValues("llm.enrichment.enabled=true", "llm.enrichment.supported-sources=CRM_DEAL")
                .run(context -> {
                    EnrichmentProperties properties = context.getBean(EnrichmentProperties.class);
                    TaskEnricher enricher = context.getBean(TaskEnricher.class);
                    DealToTaskMapper mapper = new DealToTaskMapper(TENANT_ID, properties, enricher, 14);

                    Task task = mapper.map(
                            deal("Customer asked to revise pricing."), Instant.parse("2026-11-01T00:00:00Z"));

                    assertEquals("ETL title", task.title());
                    assertEquals("Customer asked to revise pricing.", task.description());
                    assertNull(task.effortEstimateMinutes());
                });
    }
}
