package com.rnd.bridge.crm.enrichment;

import com.rnd.bridge.ai.LlmClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Bean wiring for the CRM-deal LLM enrichment collaborators that intentionally carry
 * no Spring annotations of their own - {@link EnrichmentPromptBuilder},
 * {@link EnrichmentReplyParser} and {@link CrmDealLlmEnricher} are plain,
 * framework-free classes, unit-tested without a Spring context - mirroring how
 * {@code CrmLaneConfig} treats {@code DealToTaskMapper}/{@code CrmTaskValidator} (see
 * that class's Javadoc). {@link SourceRoutingTaskEnricher} is self-registered
 * ({@code @Component @Primary}) since it has no such constraint.
 * <p>
 * {@code CrmDealLlmEnricher} resolves its {@code LlmClient} eagerly at construction
 * (same as {@code com.rnd.bridge.ai}'s own {@code SlackSummaryService} example) - this
 * bean is still always created regardless of {@code llm.enrichment.enabled}, but that
 * resolution never fails startup even with no usage configured or no API key set
 * (falls back to {@code bridge.ai.default-model}/{@code defaults}; a missing key only
 * fails the first real call, not startup) - see {@code CrmEnrichmentSpringContextTest}.
 */
@Configuration
class CrmEnrichmentConfig {

    @Bean
    EnrichmentPromptBuilder enrichmentPromptBuilder() {
        return new EnrichmentPromptBuilder();
    }

    @Bean
    EnrichmentReplyParser enrichmentReplyParser() {
        return new EnrichmentReplyParser();
    }

    @Bean
    CrmDealLlmEnricher crmDealLlmEnricher(LlmClients llmClients, EnrichmentPromptBuilder promptBuilder,
                                          EnrichmentReplyParser replyParser, Clock clock,
                                          @Value("${bridge.crm.enrichment.min-call-interval:4s}")
                                          String minCallInterval) {
        // Parsed directly with Spring Boot's own Duration parser, rather than binding @Value to a
        // Duration parameter - a plain ApplicationContextRunner test context (see
        // CrmEnrichmentSpringContextTest) has no ApplicationConversionService registered, so a
        // Duration-typed @Value parameter fails to convert even its own string default there.
        return new CrmDealLlmEnricher(
                llmClients, promptBuilder, replyParser, clock, DurationStyle.detectAndParse(minCallInterval));
    }
}
