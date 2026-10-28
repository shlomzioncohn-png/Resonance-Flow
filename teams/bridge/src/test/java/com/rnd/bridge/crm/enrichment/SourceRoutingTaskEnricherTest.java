package com.rnd.bridge.crm.enrichment;

import com.rnd.bridge.ai.LlmException;
import com.rnd.bridge.task.enrichment.EnrichmentInput;
import com.rnd.bridge.task.enrichment.EnrichmentOutput;
import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.RuleBasedTaskEnricher;
import com.rnd.bridge.task.enrichment.rules.ConfidenceCalculator;
import com.rnd.bridge.task.enrichment.rules.DateExtractor;
import com.rnd.bridge.task.enrichment.rules.UrgencyKeywordScorer;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceRoutingTaskEnricherTest {

    private static RuleBasedTaskEnricher realRuleBasedEnricher() {
        return new RuleBasedTaskEnricher(
                new UrgencyKeywordScorer(new EnrichmentProperties()), new DateExtractor(), new ConfidenceCalculator());
    }

    @Test
    void nonCrmSource_routesToRuleBasedTaskEnricher_sameResultAsCallingItDirectly() {
        RuleBasedTaskEnricher ruleBased = realRuleBasedEnricher();
        // A CrmDealLlmEnricher whose LlmClient blows up if ever invoked - proves a non-CRM
        // input never reaches the CRM path.
        FakeLlmClient neverCalled = FakeLlmClient.throwing(new LlmException("must never be called", false));
        CrmDealLlmEnricher crmEnricher = new CrmDealLlmEnricher(new FakeLlmClients(neverCalled),
                new EnrichmentPromptBuilder(), new EnrichmentReplyParser(), Clock.systemUTC(), Duration.ZERO);
        SourceRoutingTaskEnricher router = new SourceRoutingTaskEnricher(crmEnricher, ruleBased);

        EnrichmentInput githubInput = new EnrichmentInput(SourceType.GITHUB_ISSUE, null,
                "This is urgent, blocker! due 2026-12-31", TaskStatus.NEW, "gh:1", Instant.now(), null, null);

        EnrichmentOutput viaRouter = router.enrich(githubInput);
        EnrichmentOutput viaDirect = ruleBased.enrich(githubInput);

        assertEquals(viaDirect, viaRouter);
    }

    @Test
    void jiraSource_routesToRuleBasedTaskEnricher_sameResultAsCallingItDirectly() {
        RuleBasedTaskEnricher ruleBased = realRuleBasedEnricher();
        FakeLlmClient neverCalled = FakeLlmClient.throwing(new LlmException("must never be called", false));
        CrmDealLlmEnricher crmEnricher = new CrmDealLlmEnricher(new FakeLlmClients(neverCalled),
                new EnrichmentPromptBuilder(), new EnrichmentReplyParser(), Clock.systemUTC(), Duration.ZERO);
        SourceRoutingTaskEnricher router = new SourceRoutingTaskEnricher(crmEnricher, ruleBased);

        EnrichmentInput jiraInput = new EnrichmentInput(SourceType.JIRA_ISSUE, null,
                "critical regression, needed asap", TaskStatus.NEW, "jira:1", Instant.now(), null, null);

        assertEquals(ruleBased.enrich(jiraInput), router.enrich(jiraInput));
    }

    @Test
    void crmDealSource_routesToCrmDealLlmEnricher() {
        RuleBasedTaskEnricher ruleBased = realRuleBasedEnricher();
        String reply = "{\"title\": \"Prepare the proposal\", \"effortEstimateMinutes\": 30}";
        CrmDealLlmEnricher crmEnricher = new CrmDealLlmEnricher(new FakeLlmClients(FakeLlmClient.returning(reply)),
                new EnrichmentPromptBuilder(), new EnrichmentReplyParser(), Clock.systemUTC(), Duration.ZERO);
        SourceRoutingTaskEnricher router = new SourceRoutingTaskEnricher(crmEnricher, ruleBased);

        EnrichmentInput crmInput = new EnrichmentInput(SourceType.CRM_DEAL, "Original title",
                "Customer wants revised pricing.", TaskStatus.NEW, "crm:json:D-1", Instant.now(), 3, null);

        EnrichmentOutput output = router.enrich(crmInput);

        assertEquals("Prepare the proposal", output.title());
        assertEquals(30, output.effortEstimateMinutes());
    }
}
