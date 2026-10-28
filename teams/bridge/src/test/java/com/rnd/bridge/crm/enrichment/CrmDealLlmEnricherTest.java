package com.rnd.bridge.crm.enrichment;

import com.rnd.bridge.ai.LlmException;
import com.rnd.bridge.ai.LlmQuotaExhaustedException;
import com.rnd.bridge.task.enrichment.EnrichmentInput;
import com.rnd.bridge.task.enrichment.EnrichmentOutput;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrmDealLlmEnricherTest {

    private static EnrichmentInput input(TaskStatus status, String description, Instant deadline) {
        return input("Original deal title", status, description, deadline, "crm:json:D-1", Instant.now(), 3);
    }

    private static EnrichmentInput input(String title, TaskStatus status, String description, Instant deadline,
                                         String sourceRef, Instant asOf, Integer urgency) {
        return new EnrichmentInput(SourceType.CRM_DEAL, title, description, status, sourceRef, asOf, urgency,
                deadline);
    }

    private static CrmDealLlmEnricher enricherFor(FakeLlmClient client) {
        return new CrmDealLlmEnricher(new FakeLlmClients(client), new EnrichmentPromptBuilder(),
                new EnrichmentReplyParser(), Clock.systemUTC(), Duration.ZERO);
    }

    private static CrmDealLlmEnricher enricherFor(FakeLlmClient client, EnrichmentReplyCache cache) {
        return new CrmDealLlmEnricher(new FakeLlmClients(client), new EnrichmentPromptBuilder(),
                new EnrichmentReplyParser(), cache, disabledRateLimiter());
    }

    private static CallRateLimiter disabledRateLimiter() {
        return new CallRateLimiter(Duration.ZERO, Clock.systemUTC(), d -> { });
    }

    private static final String VALID_REPLY = "{\"title\": \"Prepare the proposal\", "
            + "\"description\": \"Revise pricing per the new terms.\", \"effortEstimateMinutes\": 45}";

    @Test
    void validReply_returnsEnrichedFields_withPrependedOriginalNotes() {
        FakeLlmClient client = FakeLlmClient.returning(VALID_REPLY);
        CrmDealLlmEnricher enricher = enricherFor(client);

        EnrichmentOutput output = enricher.enrich(input(TaskStatus.NEW, "Customer asked to revise pricing.", null));

        assertEquals("Prepare the proposal", output.title());
        assertEquals("Revise pricing per the new terms.\n\nOriginal CRM notes:\nCustomer asked to revise pricing.",
                output.description());
        assertEquals(45, output.effortEstimateMinutes());
        assertEquals(1, client.callCount());
    }

    @Test
    void modelReturnedNoDescription_outputDescriptionNull_keepsOriginalUnchanged() {
        FakeLlmClient client = FakeLlmClient.returning("{\"title\": \"Prepare the proposal\"}");
        CrmDealLlmEnricher enricher = enricherFor(client);

        EnrichmentOutput output = enricher.enrich(input(TaskStatus.NEW, "Customer asked to revise pricing.", null));

        assertEquals("Prepare the proposal", output.title());
        assertNull(output.description());
    }

    @Test
    void invalidJsonReply_returnsEmpty() {
        FakeLlmClient client = FakeLlmClient.returning("not json at all");
        CrmDealLlmEnricher enricher = enricherFor(client);

        EnrichmentOutput output = enricher.enrich(input(TaskStatus.NEW, "Customer asked to revise pricing.", null));

        assertTrue(output.isEmpty());
    }

    @Test
    void invalidValuesInReply_returnsEmpty() {
        FakeLlmClient client = FakeLlmClient.returning(
                "{\"title\": \"Prepare the proposal\", \"effortEstimateMinutes\": 9999}");
        CrmDealLlmEnricher enricher = enricherFor(client);

        EnrichmentOutput output = enricher.enrich(input(TaskStatus.NEW, "Customer asked to revise pricing.", null));

        assertTrue(output.isEmpty());
    }

    @Test
    void quotaExhausted_returnsEmpty() {
        FakeLlmClient client = FakeLlmClient.throwing(
                new LlmQuotaExhaustedException("quota spent", Instant.now().plusSeconds(3600), true, null));
        CrmDealLlmEnricher enricher = enricherFor(client);

        EnrichmentOutput output = enricher.enrich(input(TaskStatus.NEW, "Customer asked to revise pricing.", null));

        assertTrue(output.isEmpty());
    }

    @Test
    void timeout_returnsEmpty() {
        FakeLlmClient client = FakeLlmClient.throwing(new LlmException("simulated timeout", true));
        CrmDealLlmEnricher enricher = enricherFor(client);

        EnrichmentOutput output = enricher.enrich(input(TaskStatus.NEW, "Customer asked to revise pricing.", null));

        assertTrue(output.isEmpty());
    }

    @Test
    void terminalStatusDone_skipped_noLlmCall() {
        FakeLlmClient client = FakeLlmClient.returning("{\"title\": \"should never be used\"}");
        CrmDealLlmEnricher enricher = enricherFor(client);

        EnrichmentOutput output = enricher.enrich(input(TaskStatus.DONE, "Customer asked to revise pricing.", null));

        assertTrue(output.isEmpty());
        assertEquals(0, client.callCount());
    }

    @Test
    void terminalStatusCancelled_skipped_noLlmCall() {
        FakeLlmClient client = FakeLlmClient.returning("{\"title\": \"should never be used\"}");
        CrmDealLlmEnricher enricher = enricherFor(client);

        EnrichmentOutput output =
                enricher.enrich(input(TaskStatus.CANCELLED, "Customer asked to revise pricing.", null));

        assertTrue(output.isEmpty());
        assertEquals(0, client.callCount());
    }

    @Test
    void blankDescription_skipped_noLlmCall() {
        FakeLlmClient client = FakeLlmClient.returning("{\"title\": \"should never be used\"}");
        CrmDealLlmEnricher enricher = enricherFor(client);

        EnrichmentOutput output = enricher.enrich(input(TaskStatus.NEW, "   ", null));

        assertTrue(output.isEmpty());
        assertEquals(0, client.callCount());
    }

    @Test
    void nullDescription_skipped_noLlmCall() {
        FakeLlmClient client = FakeLlmClient.returning("{\"title\": \"should never be used\"}");
        CrmDealLlmEnricher enricher = enricherFor(client);

        EnrichmentOutput output = enricher.enrich(input(TaskStatus.NEW, null, null));

        assertTrue(output.isEmpty());
        assertEquals(0, client.callCount());
    }

    @Test
    void protectedFields_neverSetByEnricher_evenOnValidReply() {
        FakeLlmClient client = FakeLlmClient.returning(VALID_REPLY);
        CrmDealLlmEnricher enricher = enricherFor(client);

        Instant deadline = Instant.parse("2026-11-12T23:59:59Z");
        EnrichmentOutput output =
                enricher.enrich(input(TaskStatus.NEW, "Customer asked to revise pricing.", deadline));

        assertNull(output.urgency());
        assertNull(output.deadline());
        assertNull(output.confidence());
    }

    // --- Stage 5: cache ---------------------------------------------------------------

    @Test
    void unchangedDeal_oneCallAcrossRepeatedEnrichment() {
        FakeLlmClient client = FakeLlmClient.returning(VALID_REPLY);
        CrmDealLlmEnricher enricher = enricherFor(client);
        EnrichmentInput in = input(TaskStatus.NEW, "Customer asked to revise pricing.", null);

        enricher.enrich(in);
        enricher.enrich(in);
        enricher.enrich(in);

        assertEquals(1, client.callCount());
    }

    @Test
    void changedTitle_newCall() {
        FakeLlmClient client = FakeLlmClient.returning(VALID_REPLY);
        CrmDealLlmEnricher enricher = enricherFor(client);

        enricher.enrich(input("Title A", TaskStatus.NEW, "d", null, "crm:json:D-1", Instant.now(), 3));
        enricher.enrich(input("Title B", TaskStatus.NEW, "d", null, "crm:json:D-1", Instant.now(), 3));

        assertEquals(2, client.callCount());
    }

    @Test
    void changedDescription_newCall() {
        FakeLlmClient client = FakeLlmClient.returning(VALID_REPLY);
        CrmDealLlmEnricher enricher = enricherFor(client);

        enricher.enrich(input(TaskStatus.NEW, "Description A", null));
        enricher.enrich(input(TaskStatus.NEW, "Description B", null));

        assertEquals(2, client.callCount());
    }

    @Test
    void changedStatus_newCall() {
        FakeLlmClient client = FakeLlmClient.returning(VALID_REPLY);
        CrmDealLlmEnricher enricher = enricherFor(client);

        enricher.enrich(input(TaskStatus.NEW, "d", null));
        enricher.enrich(input(TaskStatus.IN_PROGRESS, "d", null));

        assertEquals(2, client.callCount());
    }

    @Test
    void changedDeadline_newCall() {
        FakeLlmClient client = FakeLlmClient.returning(VALID_REPLY);
        CrmDealLlmEnricher enricher = enricherFor(client);

        enricher.enrich(input(TaskStatus.NEW, "d", Instant.parse("2026-11-12T23:59:59Z")));
        enricher.enrich(input(TaskStatus.NEW, "d", Instant.parse("2026-12-01T23:59:59Z")));

        assertEquals(2, client.callCount());
    }

    @Test
    void changedUrgencyOnly_noNewCall() {
        FakeLlmClient client = FakeLlmClient.returning(VALID_REPLY);
        CrmDealLlmEnricher enricher = enricherFor(client);

        enricher.enrich(input("t", TaskStatus.NEW, "d", null, "crm:json:D-1", Instant.now(), 1));
        enricher.enrich(input("t", TaskStatus.NEW, "d", null, "crm:json:D-1", Instant.now(), 5));

        assertEquals(1, client.callCount());
    }

    @Test
    void changedAsOfOnly_noNewCall() {
        FakeLlmClient client = FakeLlmClient.returning(VALID_REPLY);
        CrmDealLlmEnricher enricher = enricherFor(client);

        enricher.enrich(input("t", TaskStatus.NEW, "d", null, "crm:json:D-1", Instant.parse("2026-01-01T00:00:00Z"), 3));
        enricher.enrich(input("t", TaskStatus.NEW, "d", null, "crm:json:D-1", Instant.parse("2026-06-01T00:00:00Z"), 3));

        assertEquals(1, client.callCount());
    }

    @Test
    void promptVersionChange_invalidatesCache_newCall() {
        FakeLlmClient client = FakeLlmClient.returning(VALID_REPLY);
        AtomicReference<String> promptVersion = new AtomicReference<>("v1");
        EnrichmentReplyCache cache = new EnrichmentReplyCache(Clock.systemUTC(), promptVersion::get);
        CrmDealLlmEnricher enricher = enricherFor(client, cache);
        EnrichmentInput in = input(TaskStatus.NEW, "d", null);

        enricher.enrich(in);
        promptVersion.set("v2");
        enricher.enrich(in);

        assertEquals(2, client.callCount());
    }

    @Test
    void rejectedReply_cachedTemporarily_retriedAfterExpiration() {
        FakeLlmClient client = FakeLlmClient.returning("not json at all");
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        EnrichmentReplyCache cache = new EnrichmentReplyCache(clock, () -> EnrichmentPromptBuilder.PROMPT_VERSION);
        CrmDealLlmEnricher enricher = enricherFor(client, cache);
        EnrichmentInput in = input(TaskStatus.NEW, "d", null);

        enricher.enrich(in);
        enricher.enrich(in);
        assertEquals(1, client.callCount(), "rejected reply should be cached, not retried immediately");

        clock.advance(EnrichmentReplyCache.REJECTED_TTL.plusSeconds(1));
        enricher.enrich(in);
        assertEquals(2, client.callCount(), "rejected reply should be retried once its TTL has expired");
    }

    @Test
    void quotaExhausted_retriedOnNextAttempt() {
        FakeLlmClient client = FakeLlmClient.throwing(
                new LlmQuotaExhaustedException("quota spent", Instant.now().plusSeconds(3600), true, null));
        CrmDealLlmEnricher enricher = enricherFor(client);
        EnrichmentInput in = input(TaskStatus.NEW, "d", null);

        enricher.enrich(in);
        enricher.enrich(in);

        assertEquals(2, client.callCount(), "quota exhaustion must never be cached");
    }

    @Test
    void timeout_retriedOnNextAttempt() {
        FakeLlmClient client = FakeLlmClient.throwing(new LlmException("simulated timeout", true));
        CrmDealLlmEnricher enricher = enricherFor(client);
        EnrichmentInput in = input(TaskStatus.NEW, "d", null);

        enricher.enrich(in);
        enricher.enrich(in);

        assertEquals(2, client.callCount(), "a timeout must never be cached");
    }

    @Test
    void fillingPastMaxEntries_noExceptionAndBoundedSize() {
        FakeLlmClient client = FakeLlmClient.returning(VALID_REPLY);
        int maxEntries = 5;
        EnrichmentReplyCache cache =
                new EnrichmentReplyCache(Clock.systemUTC(), () -> EnrichmentPromptBuilder.PROMPT_VERSION, maxEntries);
        CrmDealLlmEnricher enricher = enricherFor(client, cache);

        int totalDeals = maxEntries * 50;
        assertDoesNotThrow(() -> {
            for (int i = 0; i < totalDeals; i++) {
                enricher.enrich(input("t", TaskStatus.NEW, "d", null, "crm:json:D-" + i, Instant.now(), 3));
            }
        });

        assertEquals(totalDeals, client.callCount());
        assertEquals(maxEntries, cache.size());
    }

    @Test
    void cacheEviction_oldestEntryEvicted_recentEntryStillCached() {
        FakeLlmClient client = FakeLlmClient.returning(VALID_REPLY);
        int maxEntries = 3;
        EnrichmentReplyCache cache =
                new EnrichmentReplyCache(Clock.systemUTC(), () -> EnrichmentPromptBuilder.PROMPT_VERSION, maxEntries);
        CrmDealLlmEnricher enricher = enricherFor(client, cache);

        // Fill the cache to its bound, then push one more entry past it.
        for (int i = 0; i < maxEntries + 1; i++) {
            enricher.enrich(input("t", TaskStatus.NEW, "d", null, "crm:json:D-" + i, Instant.now(), 3));
        }
        assertEquals(maxEntries + 1, client.callCount());

        // The oldest entry (D-0) was evicted - re-enriching it must call the LLM again.
        enricher.enrich(input("t", TaskStatus.NEW, "d", null, "crm:json:D-0", Instant.now(), 3));
        assertEquals(maxEntries + 2, client.callCount());

        // The most recent entry (D-maxEntries) is still cached - no new call.
        enricher.enrich(input("t", TaskStatus.NEW, "d", null, "crm:json:D-" + maxEntries, Instant.now(), 3));
        assertEquals(maxEntries + 2, client.callCount());
    }

    // --- RPM throttling -----------------------------------------------------------------

    @Test
    void realCallsAreSpaced_butCacheHitsAndSkipsNeverWait() {
        FakeLlmClient client = FakeLlmClient.returning(VALID_REPLY);
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        List<Duration> sleeps = new ArrayList<>();
        CallRateLimiter rateLimiter = new CallRateLimiter(Duration.ofSeconds(4), clock, sleeps::add);
        EnrichmentReplyCache cache = new EnrichmentReplyCache(clock, () -> EnrichmentPromptBuilder.PROMPT_VERSION);
        CrmDealLlmEnricher enricher = new CrmDealLlmEnricher(new FakeLlmClients(client),
                new EnrichmentPromptBuilder(), new EnrichmentReplyParser(), cache, rateLimiter);

        // First real call: nothing to wait for yet.
        enricher.enrich(input("t", TaskStatus.NEW, "d", null, "crm:json:D-1", Instant.now(), 3));
        assertEquals(0, sleeps.size());

        // A different deal's real call, only 1s later - must wait the remaining 3s of the 4s interval.
        clock.advance(Duration.ofSeconds(1));
        enricher.enrich(input("t", TaskStatus.NEW, "d", null, "crm:json:D-2", Instant.now(), 3));
        assertEquals(1, sleeps.size());
        assertEquals(Duration.ofSeconds(3), sleeps.get(0));

        // A cache HIT for D-1 (unchanged) must never wait.
        enricher.enrich(input("t", TaskStatus.NEW, "d", null, "crm:json:D-1", Instant.now(), 3));
        assertEquals(1, sleeps.size(), "a cache hit must never wait");

        // A SKIPPED deal (terminal status) must never wait either - it never reaches the model call.
        enricher.enrich(input("t", TaskStatus.DONE, "d", null, "crm:json:D-3", Instant.now(), 3));
        assertEquals(1, sleeps.size(), "a skipped deal must never wait");
    }

    @Test
    void callAfterIntervalHasAlreadyPassed_doesNotWait() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        List<Duration> sleeps = new ArrayList<>();
        CallRateLimiter rateLimiter = new CallRateLimiter(Duration.ofSeconds(4), clock, sleeps::add);
        EnrichmentReplyCache cache = new EnrichmentReplyCache(clock, () -> EnrichmentPromptBuilder.PROMPT_VERSION);
        CrmDealLlmEnricher enricher = new CrmDealLlmEnricher(new FakeLlmClients(FakeLlmClient.returning(VALID_REPLY)),
                new EnrichmentPromptBuilder(), new EnrichmentReplyParser(), cache, rateLimiter);

        enricher.enrich(input("t", TaskStatus.NEW, "d", null, "crm:json:D-1", Instant.now(), 3));
        clock.advance(Duration.ofSeconds(5));
        enricher.enrich(input("t", TaskStatus.NEW, "d", null, "crm:json:D-2", Instant.now(), 3));

        assertEquals(0, sleeps.size());
    }

    @Test
    void interruptedWait_fallsBackToEtl_noGeminiCall_interruptFlagStaysSet() {
        FakeLlmClient client = FakeLlmClient.returning(VALID_REPLY);
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        // Simulates exactly what CallRateLimiter.realSleeper() does on a real interrupt.
        CallRateLimiter rateLimiter = new CallRateLimiter(Duration.ofSeconds(4), clock, d -> {
            Thread.currentThread().interrupt();
            throw new CallRateLimiterInterruptedException(new InterruptedException("simulated"));
        });
        EnrichmentReplyCache cache = new EnrichmentReplyCache(clock, () -> EnrichmentPromptBuilder.PROMPT_VERSION);
        CrmDealLlmEnricher enricher = new CrmDealLlmEnricher(new FakeLlmClients(client),
                new EnrichmentPromptBuilder(), new EnrichmentReplyParser(), cache, rateLimiter);

        // First real call: no prior call, so awaitTurn() never invokes the sleeper - succeeds normally.
        enricher.enrich(input("t", TaskStatus.NEW, "d", null, "crm:json:D-1", Instant.now(), 3));
        assertEquals(1, client.callCount());

        try {
            // Second call, different deal, still within the interval - this is the call that
            // invokes the (interrupting) sleeper.
            EnrichmentOutput output = enricher.enrich(
                    input("t", TaskStatus.NEW, "d", null, "crm:json:D-2", Instant.now(), 3));

            assertTrue(output.isEmpty(), "an interrupted wait must fall back to the ETL result");
            assertEquals(1, client.callCount(), "no Gemini call must be made after an interrupted wait");
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must not be swallowed");
        } finally {
            Thread.interrupted(); // clear, so this doesn't leak into later tests on this thread
        }
    }

    // --- duplicate description -----------------------------------------------------------

    @Test
    void modelDescriptionEqualsOriginal_afterWhitespaceNormalization_notPrepended() {
        String reply = "{\"title\": \"Prepare the proposal\", "
                + "\"description\": \"  Customer   asked to revise pricing.  \"}";
        FakeLlmClient client = FakeLlmClient.returning(reply);
        CrmDealLlmEnricher enricher = enricherFor(client);

        EnrichmentOutput output = enricher.enrich(input(TaskStatus.NEW, "Customer asked to revise pricing.", null));

        assertNull(output.description());
    }

    @Test
    void originalDescriptionContainsModelDescriptionEntirely_notPrepended() {
        String reply = "{\"title\": \"Prepare the proposal\", \"description\": \"revise pricing\"}";
        FakeLlmClient client = FakeLlmClient.returning(reply);
        CrmDealLlmEnricher enricher = enricherFor(client);

        EnrichmentOutput output = enricher.enrich(
                input(TaskStatus.NEW, "Customer asked to revise pricing before month end.", null));

        assertNull(output.description());
    }
}
