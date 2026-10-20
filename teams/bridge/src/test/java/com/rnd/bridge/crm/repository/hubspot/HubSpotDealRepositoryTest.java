package com.rnd.bridge.crm.repository.hubspot;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
import com.rnd.bridge.crm.repository.DealSnapshot;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link HubSpotDealRepository} against a {@link MockWebServer} with recorded-shape
 * HubSpot response bodies. No test in this class talks to the live HubSpot API.
 * <p>
 * Every test now goes through {@link HubSpotDealRepository#snapshot()} - the deprecated
 * per-instance-cache finder methods (and the cache field behind them) were removed entirely
 * (CRM-lane audit finding F1). Because {@code snapshot()} does one fully independent fetch -
 * pipeline AND deals - on every call, and the constructor still does its own one-off pipeline
 * fetch purely to fail fast at startup, every test here enqueues the pipeline TWICE: once for the
 * constructor, once for the {@code snapshot()} call under test.
 * <p>
 * Stage mapping is metadata-based (F6), not label-based - see {@link HubSpotStageMappingTest} for
 * the mapping-rule unit tests (bands, metadata leniency/validation, invalid-stage isolation). This
 * class's pipeline fixtures carry {@code metadata} on every stage for that reason; a fixture that
 * deliberately omits it is exercising one of the FAIL/SKIP paths that metadata absence triggers.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class HubSpotDealRepositoryTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-30T12:00:00Z");

    private MockWebServer server;
    private final List<ListAppender<ILoggingEvent>> attachedAppenders = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
        Logger repoLogger = (Logger) LoggerFactory.getLogger(HubSpotDealRepository.class);
        Logger mappingLogger = (Logger) LoggerFactory.getLogger(HubSpotStageMapping.class);
        attachedAppenders.forEach(repoLogger::detachAppender);
        attachedAppenders.forEach(mappingLogger::detachAppender);
        attachedAppenders.clear();
    }

    private ListAppender<ILoggingEvent> captureLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(HubSpotDealRepository.class)).addAppender(appender);
        ((Logger) LoggerFactory.getLogger(HubSpotStageMapping.class)).addAppender(appender);
        attachedAppenders.add(appender);
        return appender;
    }

    private static HubSpotStageMapping defaultStageMapping() {
        return new HubSpotStageMapping(20, 50, 70, 99);
    }

    private HubSpotDealRepository newRepository() {
        return newRepository(5, 0L, 0L);
    }

    private HubSpotDealRepository newRepository(int maxRetries, long initialBackoffMillis, long maxBackoffMillis) {
        return newRepositoryWithClock(Clock.fixed(FIXED_NOW, ZoneOffset.UTC), maxRetries, initialBackoffMillis, maxBackoffMillis);
    }

    private HubSpotDealRepository newRepositoryWithClock(Clock clock) {
        return newRepositoryWithClock(clock, 5, 0L, 0L);
    }

    private HubSpotDealRepository newRepositoryWithClock(
            Clock clock, int maxRetries, long initialBackoffMillis, long maxBackoffMillis) {
        return newRepositoryWithClockAndRatio(clock, maxRetries, initialBackoffMillis, maxBackoffMillis, 0.5);
    }

    private HubSpotDealRepository newRepositoryWithRatio(double maxSkippedRatio) {
        return newRepositoryWithClockAndRatio(Clock.fixed(FIXED_NOW, ZoneOffset.UTC), 5, 0L, 0L, maxSkippedRatio);
    }

    private HubSpotDealRepository newRepositoryWithClockAndRatio(
            Clock clock, int maxRetries, long initialBackoffMillis, long maxBackoffMillis, double maxSkippedRatio) {
        HubSpotClient client = new HubSpotClient(
                RestClient.builder(),
                server.url("/").toString(),
                "test-token",
                100,
                maxRetries,
                initialBackoffMillis,
                maxBackoffMillis,
                millis -> { });
        return new HubSpotDealRepository(client, clock, defaultStageMapping(), maxSkippedRatio);
    }

    private void enqueueJson(String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    /**
     * Pipeline with stage ids that look nothing like HubSpot's default slugs, carrying the
     * probabilities Step 0 confirmed against the live default pipeline - open stages 20/40/60/80/90,
     * closed 100/0 - which map identically to today's (pre-F6) label-based mapping (PROSPECT,
     * QUALIFIED, PROPOSAL, NEGOTIATION, NEGOTIATION, CLOSED_WON, CLOSED_LOST).
     */
    private static final String DEFAULT_PIPELINE = """
            {
              "results": [
                {
                  "id": "pl-1",
                  "label": "Sales Pipeline",
                  "stages": [
                    {"id": "st-100", "label": "Appointment Scheduled", "metadata": {"isClosed": "false", "probability": "0.2"}},
                    {"id": "st-200", "label": "Qualified To Buy", "metadata": {"isClosed": "false", "probability": "0.4"}},
                    {"id": "st-300", "label": "Presentation Scheduled", "metadata": {"isClosed": "false", "probability": "0.6"}},
                    {"id": "st-400", "label": "Decision Maker Bought-In", "metadata": {"isClosed": "false", "probability": "0.8"}},
                    {"id": "st-450", "label": "Contract Sent", "metadata": {"isClosed": "false", "probability": "0.9"}},
                    {"id": "st-500", "label": "Closed Won", "metadata": {"isClosed": "true", "probability": "1.0"}},
                    {"id": "st-600", "label": "Closed Lost", "metadata": {"isClosed": "true", "probability": "0.0"}}
                  ]
                }
              ]
            }
            """;

    /** Consumed by the constructor's own fail-fast pipeline fetch. */
    private void enqueueConstructorPipeline() {
        enqueueJson(DEFAULT_PIPELINE);
    }

    /** Consumed by a {@code snapshot()} call's own fresh pipeline fetch. */
    private void enqueuePipeline() {
        enqueueJson(DEFAULT_PIPELINE);
    }

    private static String historyEntry(String stageId, String timestamp) {
        return "{\"value\":\"" + stageId + "\",\"timestamp\":\"" + timestamp + "\"}";
    }

    private static String dealJson(
            String id, String dealname, String amount, String closedate, String createdAt,
            String historyEntriesNewestFirstCsv) {
        return """
                {
                  "id": "%s",
                  "createdAt": "%s",
                  "properties": {
                    "dealname": %s,
                    "amount": %s,
                    "closedate": %s,
                    "dealstage": "current"
                  },
                  "propertiesWithHistory": {
                    "dealstage": [%s]
                  }
                }
                """.formatted(
                id, createdAt,
                jsonString(dealname), jsonString(amount), jsonString(closedate),
                historyEntriesNewestFirstCsv);
    }

    private static String jsonString(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static String dealsPage(String pagingNextAfter, String... dealsJson) {
        StringBuilder results = new StringBuilder();
        for (int i = 0; i < dealsJson.length; i++) {
            if (i > 0) {
                results.append(',');
            }
            results.append(dealsJson[i]);
        }
        String paging = pagingNextAfter == null
                ? "null"
                : "{\"next\": {\"after\": \"" + pagingNextAfter + "\"}}";
        return "{\"results\": [" + results + "], \"paging\": " + paging + "}";
    }

    // ==== 1:1 adaptations of every HEAD scenario (metadata fixtures + new constructor) ====

    @Test
    void dealWithSeveralStageTransitionsProducesRecordsOrderedAscendingByStageChangedAt() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        enqueuePipeline();
        String deal = dealJson("D-1", "Acme deal", "50000", "2026-09-15T00:00:00.000Z", "2026-05-01T08:00:00Z",
                String.join(",",
                        historyEntry("st-500", "2026-08-20T10:00:00.000Z"),
                        historyEntry("st-450", "2026-08-10T09:00:00.000Z"),
                        historyEntry("st-100", "2026-05-01T08:00:00.000Z")));
        enqueueJson(dealsPage(null, deal));

        List<Deal> history = repository.snapshot().findHistoryByDealId("D-1");

        assertEquals(
                List.of(DealStage.PROSPECT, DealStage.NEGOTIATION, DealStage.CLOSED_WON),
                history.stream().map(Deal::stage).toList());
        assertEquals(
                List.of(
                        Instant.parse("2026-05-01T08:00:00Z"),
                        Instant.parse("2026-08-10T09:00:00Z"),
                        Instant.parse("2026-08-20T10:00:00Z")),
                history.stream().map(Deal::stageChangedAt).toList());
    }

    @Test
    void reopenedDealAfterClosedWonProducesRecordsInTheOrderItHappenedWithPostReopenStageLast() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        enqueuePipeline();
        String deal = dealJson("D-2", "Reopened deal", "10000", null, "2026-04-01T08:00:00Z",
                String.join(",",
                        historyEntry("st-400", "2026-08-15T09:00:00.000Z"),
                        historyEntry("st-500", "2026-08-05T09:00:00.000Z"),
                        historyEntry("st-200", "2026-04-01T08:00:00.000Z")));
        enqueueJson(dealsPage(null, deal));

        DealSnapshot snapshot = repository.snapshot();
        List<Deal> history = snapshot.findHistoryByDealId("D-2");

        assertEquals(
                List.of(DealStage.QUALIFIED, DealStage.CLOSED_WON, DealStage.NEGOTIATION),
                history.stream().map(Deal::stage).toList());

        Deal latest = snapshot.findLatestByDealId("D-2").orElseThrow();
        assertEquals(DealStage.NEGOTIATION, latest.stage());
        assertEquals(history.get(2), latest);
    }

    @Test
    void dealWithOneHistoryEntryProducesOneRecord() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        enqueuePipeline();
        String deal = dealJson("D-3", "Single event deal", "5000", "2026-10-01T00:00:00.000Z",
                "2026-08-01T08:00:00Z", historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, deal));

        List<Deal> history = repository.snapshot().findHistoryByDealId("D-3");

        assertEquals(1, history.size());
        assertEquals(DealStage.PROSPECT, history.get(0).stage());
    }

    @Test
    void sourceIsHubspot() {
        enqueueConstructorPipeline();

        assertEquals("hubspot", newRepository().source());
    }

    @Test
    void dealsCarryTheExplicitHubspotSourceRefPrefix() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        enqueuePipeline();
        String deal = dealJson("D-5", "Prefix check deal", "1000", "2026-10-01T00:00:00.000Z",
                "2026-08-01T08:00:00Z", historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, deal));

        Deal only = repository.snapshot().findLatestByDealId("D-5").orElseThrow();

        assertEquals("crm:hubspot:", only.sourceRefPrefix());
    }

    @Test
    void dealWithNoCloseDateAndNoAmountRoundTripsAsNullsNotDefaults() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        enqueuePipeline();
        String deal = dealJson("D-4", "No close date or amount", null, null, "2026-08-01T08:00:00Z",
                historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, deal));

        Deal only = repository.snapshot().findLatestByDealId("D-4").orElseThrow();

        assertNull(only.closeDate());
        assertNull(only.dealValue());
    }

    @Test
    void stageIdsResolveThroughTheFetchedPipelineMappingNotAHardcodedTable() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        // Same labels as HubSpot's default pipeline, but with stage ids that would not appear in
        // any hardcoded id table - and metadata, not label, is what actually resolves them.
        String pipelineWithUnusualIds = """
                {
                  "results": [
                    {
                      "id": "pl-custom",
                      "label": "Custom Pipeline",
                      "stages": [
                        {"id": "zzz-1", "label": "Appointment Scheduled", "metadata": {"isClosed": "false", "probability": "0.2"}},
                        {"id": "zzz-6", "label": "Closed Won", "metadata": {"isClosed": "true", "probability": "1.0"}}
                      ]
                    }
                  ]
                }
                """;
        enqueueJson(pipelineWithUnusualIds);
        String deal = dealJson("D-5", "Unusual ids", "1000", null, "2026-08-01T08:00:00Z",
                String.join(",",
                        historyEntry("zzz-6", "2026-08-10T08:00:00.000Z"),
                        historyEntry("zzz-1", "2026-08-01T08:00:00.000Z")));
        enqueueJson(dealsPage(null, deal));

        List<Deal> history = repository.snapshot().findHistoryByDealId("D-5");

        assertEquals(List.of(DealStage.PROSPECT, DealStage.CLOSED_WON),
                history.stream().map(Deal::stage).toList());
    }

    @Test
    void unknownStageIdReferencedByADealIsSkippedNotFailingTheWholeSync() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        enqueuePipeline();
        String badDeal = dealJson("D-6", "Bad stage", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-999-does-not-exist", "2026-08-01T08:00:00.000Z"));
        String goodDeal = dealJson("D-6b", "Good deal alongside it", "2000", null, "2026-08-02T08:00:00Z",
                historyEntry("st-100", "2026-08-02T08:00:00.000Z"));
        enqueueJson(dealsPage(null, badDeal, goodDeal));

        DealSnapshot snapshot = assertDoesNotThrow(repository::snapshot);

        assertEquals(List.of("D-6b"), snapshot.allDeals().stream().map(Deal::dealId).toList());
        assertEquals(1, snapshot.skippedAtRead());
    }

    /**
     * Replaces HEAD's {@code pipelineStageWithAnUnrecognizedLabelFailsAtConstruction}. That test
     * proved an unrecognized LABEL failed construction - the exact F6 weakness this task fixes.
     * Mapping is now metadata-based, so a custom/renamed label with valid metadata must SUCCEED
     * instead - this is that same scenario, with the opposite (now correct) expectation.
     */
    @Test
    void renamedOrCustomStageLabelWithValidMetadataResolvesAtConstructionInsteadOfFailing() {
        String pipelineWithCustomStage = """
                {
                  "results": [
                    {
                      "id": "pl-1",
                      "label": "Sales Pipeline",
                      "stages": [
                        {"id": "st-1", "label": "Contract Out", "metadata": {"isClosed": "false", "probability": "0.9"}}
                      ]
                    }
                  ]
                }
                """;
        enqueueJson(pipelineWithCustomStage);

        HubSpotDealRepository repository = assertDoesNotThrow(() -> newRepository());

        enqueueJson(pipelineWithCustomStage);
        String deal = dealJson("D-99", "On the renamed stage", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-1", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, deal));

        DealSnapshot snapshot = repository.snapshot();

        assertEquals(DealStage.NEGOTIATION, snapshot.findLatestByDealId("D-99").orElseThrow().stage());
        assertEquals(0, snapshot.skippedAtRead());
    }

    /**
     * Replaces HEAD's {@code secondSnapshotFailsEntirelyWhenTheRefetchedPipelineHasAnUnrecognizedStageLabel}.
     * That test proved a brand-new custom-labeled stage picked up on a refetch failed the ENTIRE
     * snapshot (F6's core weakness). Metadata-based mapping fixes exactly this: the same scenario
     * now succeeds, proving freshness (F1/F14) and the F6 fix together.
     */
    @Test
    void secondSnapshotPicksUpABrandNewCustomLabeledStageByItsMetadataNotFailingTheWholeSync() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        String pipelineWithANewCustomStage = """
                {
                  "results": [
                    {
                      "id": "pl-1",
                      "label": "Sales Pipeline",
                      "stages": [
                        {"id": "st-100", "label": "Appointment Scheduled", "metadata": {"isClosed": "false", "probability": "0.2"}},
                        {"id": "st-999", "label": "Some Brand New Custom Stage", "metadata": {"isClosed": "false", "probability": "0.95"}}
                      ]
                    }
                  ]
                }
                """;
        enqueueJson(pipelineWithANewCustomStage);
        String deal = dealJson("D-100", "On the new custom stage", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-999", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, deal));

        DealSnapshot snapshot = assertDoesNotThrow(repository::snapshot);

        assertEquals(DealStage.NEGOTIATION, snapshot.findLatestByDealId("D-100").orElseThrow().stage());
        assertEquals(0, snapshot.skippedAtRead());
    }

    @Test
    void paginationFollowsTheCursorAndConsumesBothPages() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        enqueuePipeline();
        String dealOnPageOne = dealJson("D-7", "Page one deal", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        String dealOnPageTwo = dealJson("D-8", "Page two deal", "2000", null, "2026-08-02T08:00:00Z",
                historyEntry("st-200", "2026-08-02T08:00:00.000Z"));
        enqueueJson(dealsPage("cursor-2", dealOnPageOne));
        enqueueJson(dealsPage(null, dealOnPageTwo));

        List<String> dealIds = repository.snapshot().dealIds();

        assertEquals(List.of("D-7", "D-8"), dealIds);
        assertEquals(4, server.getRequestCount()); // constructor pipeline + snapshot pipeline + 2 deal pages

        RecordedRequest secondPageRequest = takeRequest(4); // constructor pipeline, snapshot pipeline, page 1, page 2
        assertTrue(secondPageRequest.getPath().contains("after=cursor-2"));
    }

    @Test
    void aTwoNineIsRetriedRatherThanPropagated() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        enqueuePipeline();
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "0")
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"error\",\"message\":\"rate limited\"}"));
        String deal = dealJson("D-9", "Survives a 429", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, deal));

        List<Deal> all = repository.snapshot().allDeals();

        assertEquals(1, all.size());
        assertEquals("D-9", all.get(0).dealId());
    }

    @Test
    void exhaustingTheRetryBudgetOnRepeated429sFailsWithAClearError() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository(3, 0L, 0L);

        enqueuePipeline();
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse()
                    .setResponseCode(429)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{}"));
        }

        HubSpotClientException exception = assertThrows(HubSpotClientException.class, repository::snapshot);
        assertTrue(exception.getMessage().contains("rate limit"));
        assertTrue(exception.getMessage().contains("/crm/v3/objects/deals"));
        assertTrue(exception.getMessage().toLowerCase().indexOf("test-token") < 0);
    }

    @Test
    void aFiveOhThreeIsRetriedRatherThanPropagated() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        enqueuePipeline();
        server.enqueue(new MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"error\",\"message\":\"service unavailable\"}"));
        String deal = dealJson("D-11", "Survives a 503", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, deal));

        List<Deal> all = repository.snapshot().allDeals();

        assertEquals(1, all.size());
        assertEquals("D-11", all.get(0).dealId());
    }

    @Test
    void exhaustingTheRetryBudgetOnRepeatedFiveOhFoursFailsWithAClearError() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository(3, 0L, 0L);

        enqueuePipeline();
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse()
                    .setResponseCode(504)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{}"));
        }

        HubSpotClientException exception = assertThrows(HubSpotClientException.class, repository::snapshot);
        assertTrue(exception.getMessage().contains("504"));
        assertTrue(exception.getMessage().contains("/crm/v3/objects/deals"));
        assertTrue(exception.getMessage().toLowerCase().indexOf("test-token") < 0);
    }

    @Test
    void aFatalFourOhFourFailsImmediatelyWithoutRetrying() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        enqueuePipeline();
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"error\",\"message\":\"not found\"}"));

        HubSpotClientException exception = assertThrows(HubSpotClientException.class, repository::snapshot);
        assertTrue(exception.getMessage().contains("404"));
        assertEquals(3, server.getRequestCount()); // constructor pipeline + snapshot pipeline + one non-retried deal request
    }

    @Test
    void asOfComesFromTheClockAtTheStartOfTheSnapshotsFetch() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        enqueuePipeline();
        String deal = dealJson("D-10", "Stable asOf", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, deal));

        assertEquals(FIXED_NOW, repository.snapshot().asOf());
    }

    private RecordedRequest takeRequest(int count) {
        RecordedRequest last = null;
        for (int i = 0; i < count; i++) {
            try {
                last = server.takeRequest();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return last;
    }

    // ---- snapshot(): F1/F14 - a fresh fetch every call, never cached across calls ----

    @Test
    void secondSnapshotFetchesFreshDataAndReflectsAChangedStage() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepositoryWithClock(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        enqueuePipeline(); // snapshot #1's own fresh pipeline fetch
        String dealV1 = dealJson("D-20", "Drifts over time", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, dealV1));

        DealSnapshot first = repository.snapshot();
        assertEquals(DealStage.PROSPECT, first.findLatestByDealId("D-20").orElseThrow().stage());

        enqueuePipeline(); // snapshot #2's own fresh pipeline fetch
        String dealV2 = dealJson("D-20", "Drifts over time", "1000", null, "2026-08-01T08:00:00Z",
                String.join(",",
                        historyEntry("st-500", "2026-08-28T09:00:00.000Z"),
                        historyEntry("st-100", "2026-08-01T08:00:00.000Z")));
        enqueueJson(dealsPage(null, dealV2));

        DealSnapshot second = repository.snapshot();

        assertEquals(DealStage.CLOSED_WON, second.findLatestByDealId("D-20").orElseThrow().stage());
        assertEquals(5, server.getRequestCount(),
                "constructor pipeline fetch + (pipeline + deals) for each of the two snapshot() calls");
    }

    @Test
    void asOfOfTheSecondSnapshotIsLaterWhenTheClockAdvances() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-30T12:00:00Z"));
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepositoryWithClock(clock);

        enqueuePipeline();
        String deal = dealJson("D-21", "Stable deal", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, deal));
        Instant firstAsOf = repository.snapshot().asOf();

        clock.advanceTo(Instant.parse("2026-09-05T08:00:00Z"));
        enqueuePipeline();
        enqueueJson(dealsPage(null, deal));
        Instant secondAsOf = repository.snapshot().asOf();

        assertEquals(Instant.parse("2026-08-30T12:00:00Z"), firstAsOf);
        assertEquals(Instant.parse("2026-09-05T08:00:00Z"), secondAsOf);
        assertTrue(secondAsOf.isAfter(firstAsOf));
    }

    @Test
    void secondSnapshotPicksUpAStageIdAddedToThePipelineAfterTheFirstSnapshot() {
        // F6 is out of scope here (the mapping RULES don't change) - this is about F1/F14's
        // freshness: the deprecated/cached path would resolve stage ids once, at construction, and
        // never see a stage id added later. snapshot() re-fetches the pipeline every call, so it
        // does.
        enqueueConstructorPipeline(); // only the seven original default-pipeline stages
        HubSpotDealRepository repository = newRepositoryWithClock(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        enqueuePipeline();
        String dealOnOriginalStage = dealJson("D-22", "Original stage", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, dealOnOriginalStage));
        repository.snapshot();

        String pipelineWithStageAddedLater = """
                {
                  "results": [
                    {
                      "id": "pl-1",
                      "label": "Sales Pipeline",
                      "stages": [
                        {"id": "st-100", "label": "Appointment Scheduled", "metadata": {"isClosed": "false", "probability": "0.2"}},
                        {"id": "st-700-new", "label": "Closed Won", "metadata": {"isClosed": "true", "probability": "1.0"}}
                      ]
                    }
                  ]
                }
                """;
        enqueueJson(pipelineWithStageAddedLater);
        String dealOnNewStage = dealJson("D-23", "On the newly added stage", "2000", null, "2026-08-20T08:00:00Z",
                historyEntry("st-700-new", "2026-08-20T08:00:00.000Z"));
        enqueueJson(dealsPage(null, dealOnNewStage));

        DealSnapshot second = repository.snapshot();

        assertEquals(DealStage.CLOSED_WON, second.findLatestByDealId("D-23").orElseThrow().stage());
        assertEquals(0, second.skippedAtRead());
    }

    @Test
    void snapshotCountsRecordsSkippedWhileParsingWithoutFailingTheWholeFetch() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepositoryWithClock(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        enqueuePipeline();
        String badDeal = dealJson("D-30", "Bad stage", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-999-does-not-exist", "2026-08-01T08:00:00.000Z"));
        String goodDeal = dealJson("D-31", "Good deal alongside it", "2000", null, "2026-08-02T08:00:00Z",
                historyEntry("st-100", "2026-08-02T08:00:00.000Z"));
        enqueueJson(dealsPage(null, badDeal, goodDeal));

        DealSnapshot snapshot = repository.snapshot();

        assertEquals(List.of("D-31"), snapshot.dealIds());
        assertEquals(1, snapshot.skippedAtRead());
    }

    @Test
    void snapshotKeepsFullHistoryWhileLatestPerDealReturnsOnlyTheLastStagePerDeal() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepositoryWithClock(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        enqueuePipeline();
        String deal = dealJson("D-40", "Reopened", "1000", null, "2026-08-01T08:00:00Z",
                String.join(",",
                        historyEntry("st-500", "2026-08-20T10:00:00.000Z"),
                        historyEntry("st-400", "2026-08-10T09:00:00.000Z"),
                        historyEntry("st-100", "2026-08-01T08:00:00.000Z")));
        enqueueJson(dealsPage(null, deal));

        DealSnapshot snapshot = repository.snapshot();

        assertEquals(3, snapshot.findHistoryByDealId("D-40").size());
        assertEquals(1, snapshot.latestPerDeal().size());
        assertEquals(DealStage.CLOSED_WON, snapshot.latestPerDeal().get(0).stage());
    }

    // ==== new policy rows (F6 commit) ====

    // ---- pipeline/stage-level FAIL STARTUP rows (#2 empty pipelines, #3 missing stage id, #5 all invalid) ----

    @Test
    void zeroPipelinesReturnedFailsAtConstruction() {
        enqueueJson("{\"results\": []}");

        assertThrows(UnknownHubSpotStageException.class, this::newRepository);
    }

    @Test
    void stageWithNoIdFailsAtConstruction() {
        String pipelineWithNoStageId = """
                {
                  "results": [
                    {
                      "id": "pl-1",
                      "label": "Sales Pipeline",
                      "stages": [
                        {"id": null, "label": "Mystery Stage", "metadata": {"isClosed": "false", "probability": "0.2"}}
                      ]
                    }
                  ]
                }
                """;
        enqueueJson(pipelineWithNoStageId);

        UnknownHubSpotStageException exception = assertThrows(UnknownHubSpotStageException.class, this::newRepository);
        assertTrue(exception.getMessage().contains("Mystery Stage"));
    }

    @Test
    void everyStageMissingMetadataFailsAtConstructionAsALikelyApiShapeChange() {
        String pipelineWithNoMetadataAnywhere = """
                {
                  "results": [
                    {
                      "id": "pl-1",
                      "label": "Sales Pipeline",
                      "stages": [
                        {"id": "st-100", "label": "Appointment Scheduled"},
                        {"id": "st-500", "label": "Closed Won"}
                      ]
                    }
                  ]
                }
                """;
        enqueueJson(pipelineWithNoMetadataAnywhere);

        assertThrows(UnknownHubSpotStageException.class, this::newRepository);
    }

    @Test
    void everyStageMissingMetadataFailsTheWholeSnapshotNotJustOneDeal() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        String pipelineWithNoMetadataAnywhere = """
                {
                  "results": [
                    {
                      "id": "pl-1",
                      "label": "Sales Pipeline",
                      "stages": [
                        {"id": "st-100", "label": "Appointment Scheduled"}
                      ]
                    }
                  ]
                }
                """;
        enqueueJson(pipelineWithNoMetadataAnywhere);

        assertThrows(UnknownHubSpotStageException.class, repository::snapshot);
    }

    // ---- SKIP rows: one invalid stage is isolated to the deals that reference it ----

    @Test
    void constructorDoesNotThrowForOneInvalidStageOnlyLogsOneWarnListingItAndTheReason() {
        String pipelineWithOneBadStage = """
                {
                  "results": [
                    {
                      "id": "pl-1",
                      "label": "Sales Pipeline",
                      "stages": [
                        {"id": "st-100", "label": "Appointment Scheduled", "metadata": {"isClosed": "false", "probability": "0.2"}},
                        {"id": "st-bad", "label": "Broken Stage"}
                      ]
                    }
                  ]
                }
                """;
        enqueueJson(pipelineWithOneBadStage);
        ListAppender<ILoggingEvent> logs = captureLogs();

        assertDoesNotThrow(() -> newRepository());

        assertTrue(logs.list.stream().anyMatch(event ->
                "WARN".equals(event.getLevel().toString())
                        && event.getFormattedMessage().contains("st-bad")));
    }

    @Test
    void oneInvalidStageSkipsOnlyTheDealsThatReferenceItAndLogsOneWarnAtResolutionTime() {
        String pipelineWithOneBadStage = """
                {
                  "results": [
                    {
                      "id": "pl-1",
                      "label": "Sales Pipeline",
                      "stages": [
                        {"id": "st-100", "label": "Appointment Scheduled", "metadata": {"isClosed": "false", "probability": "0.2"}},
                        {"id": "st-bad", "label": "Broken Stage"}
                      ]
                    }
                  ]
                }
                """;
        enqueueJson(pipelineWithOneBadStage);
        HubSpotDealRepository repository = newRepository();

        enqueueJson(pipelineWithOneBadStage);
        ListAppender<ILoggingEvent> logs = captureLogs();
        String badDeal = dealJson("D-1", "On the broken stage", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-bad", "2026-08-01T08:00:00.000Z"));
        String goodDeal = dealJson("D-2", "Fine", "2000", null, "2026-08-02T08:00:00Z",
                historyEntry("st-100", "2026-08-02T08:00:00.000Z"));
        enqueueJson(dealsPage(null, badDeal, goodDeal));

        DealSnapshot snapshot = repository.snapshot();

        assertEquals(List.of("D-2"), snapshot.dealIds());
        assertEquals(1, snapshot.skippedAtRead());
        assertTrue(logs.list.stream().anyMatch(event ->
                        "WARN".equals(event.getLevel().toString())
                                && event.getFormattedMessage().contains("st-bad")),
                "the stage-level WARN must fire, not just the counter");
    }

    @Test
    void openStageWithMissingProbabilityDefaultsToProspectAndLogsTheWarn() {
        String pipelineWithMissingProbability = """
                {
                  "results": [
                    {
                      "id": "pl-1",
                      "label": "Sales Pipeline",
                      "stages": [
                        {"id": "st-np", "label": "No Probability Set", "metadata": {"isClosed": "false"}}
                      ]
                    }
                  ]
                }
                """;
        enqueueJson(pipelineWithMissingProbability);
        HubSpotDealRepository repository = newRepository();

        enqueueJson(pipelineWithMissingProbability);
        ListAppender<ILoggingEvent> logs = captureLogs();
        String deal = dealJson("D-1", "On the no-probability stage", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-np", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, deal));

        DealSnapshot snapshot = repository.snapshot();

        assertEquals(DealStage.PROSPECT, snapshot.findLatestByDealId("D-1").orElseThrow().stage());
        assertEquals(0, snapshot.skippedAtRead());
        assertTrue(logs.list.stream().anyMatch(event ->
                "WARN".equals(event.getLevel().toString())
                        && event.getFormattedMessage().contains("st-np")));
    }

    // ---- threshold: too many skipped deals fails the whole run ----

    @Test
    void manyDealsStuckOnOneInvalidStageAreAllSkippedAndCountedWhenBelowTheThreshold() {
        String pipelineWithOneBadStage = """
                {
                  "results": [
                    {
                      "id": "pl-1",
                      "label": "Sales Pipeline",
                      "stages": [
                        {"id": "st-100", "label": "Appointment Scheduled", "metadata": {"isClosed": "false", "probability": "0.2"}},
                        {"id": "st-bad", "label": "Broken Stage"}
                      ]
                    }
                  ]
                }
                """;
        enqueueJson(pipelineWithOneBadStage);
        HubSpotDealRepository repository = newRepositoryWithRatio(0.5);

        enqueueJson(pipelineWithOneBadStage);
        List<String> dealsJson = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            dealsJson.add(dealJson("BAD-" + i, "Stuck", "1000", null, "2026-08-01T08:00:00Z",
                    historyEntry("st-bad", "2026-08-01T08:00:00.000Z")));
        }
        for (int i = 0; i < 3; i++) {
            dealsJson.add(dealJson("GOOD-" + i, "Fine", "1000", null, "2026-08-01T08:00:00Z",
                    historyEntry("st-100", "2026-08-01T08:00:00.000Z")));
        }
        enqueueJson(dealsPage(null, dealsJson.toArray(new String[0])));

        // 2 skipped / 5 total = 0.4, not strictly greater than the 0.5 threshold - passes.
        DealSnapshot snapshot = repository.snapshot();

        assertEquals(2, snapshot.skippedAtRead());
        assertEquals(3, snapshot.allDeals().size());
    }

    @Test
    void exceedingTheSkipThresholdFailsTheWholeRunInsteadOfPublishingAPartialSnapshot() {
        String pipelineWithOneBadStage = """
                {
                  "results": [
                    {
                      "id": "pl-1",
                      "label": "Sales Pipeline",
                      "stages": [
                        {"id": "st-100", "label": "Appointment Scheduled", "metadata": {"isClosed": "false", "probability": "0.2"}},
                        {"id": "st-bad", "label": "Broken Stage"}
                      ]
                    }
                  ]
                }
                """;
        enqueueJson(pipelineWithOneBadStage);
        HubSpotDealRepository repository = newRepositoryWithRatio(0.5);

        enqueueJson(pipelineWithOneBadStage);
        List<String> dealsJson = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            dealsJson.add(dealJson("BAD-" + i, "Stuck", "1000", null, "2026-08-01T08:00:00Z",
                    historyEntry("st-bad", "2026-08-01T08:00:00.000Z")));
        }
        for (int i = 0; i < 2; i++) {
            dealsJson.add(dealJson("GOOD-" + i, "Fine", "1000", null, "2026-08-01T08:00:00Z",
                    historyEntry("st-100", "2026-08-01T08:00:00.000Z")));
        }
        enqueueJson(dealsPage(null, dealsJson.toArray(new String[0])));

        // 3 skipped / 5 total = 0.6, strictly greater than 0.5 - fails the whole run.
        assertThrows(HubSpotSkipThresholdExceededException.class, repository::snapshot);
    }

    @Test
    void exactlyAtTheThresholdRatioPasses() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepositoryWithRatio(0.5);

        enqueuePipeline();
        String badDeal = dealJson("D-1", "Bad", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-999-does-not-exist", "2026-08-01T08:00:00.000Z"));
        String goodDeal = dealJson("D-2", "Good", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, badDeal, goodDeal));

        // 1 skipped / 2 total = 0.5, not STRICTLY greater than 0.5 - passes.
        DealSnapshot snapshot = assertDoesNotThrow(repository::snapshot);
        assertEquals(1, snapshot.skippedAtRead());
    }

    @Test
    void zeroDealsReadNeverFailsTheThresholdCheck() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepositoryWithRatio(0.5);

        enqueuePipeline();
        enqueueJson(dealsPage(null));

        DealSnapshot snapshot = assertDoesNotThrow(repository::snapshot);
        assertEquals(0, snapshot.skippedAtRead());
        assertEquals(0, snapshot.allDeals().size());
    }

    @Test
    void oneOfOneSkippedFailsTheThresholdCheck() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepositoryWithRatio(0.5);

        enqueuePipeline();
        String badDeal = dealJson("D-1", "Bad", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-999-does-not-exist", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, badDeal));

        // 1 skipped / 1 total = 1.0, strictly greater than 0.5 - fails.
        assertThrows(HubSpotSkipThresholdExceededException.class, repository::snapshot);
    }

    @Test
    void maxSkippedRatioMustBeGreaterThanZeroAndAtMostOne() {
        for (double invalid : new double[] {0.0, -0.1, 1.1}) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(DEFAULT_PIPELINE));
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> newRepositoryWithRatio(invalid));
            assertTrue(exception.getMessage().contains("max-skipped-ratio"), "ratio " + invalid);
        }
    }

    // ---- fetchDeal(String): a fresh, single-record lookup, never cached, never reusing snapshot() data ----

    @Test
    void fetchDealReturnsTheLatestStateOnASuccessfulResponse() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        String deal = dealJson("D-50", "Fetched directly", "7000", "2026-09-01T00:00:00.000Z", "2026-08-01T08:00:00Z",
                String.join(",",
                        historyEntry("st-500", "2026-08-20T10:00:00.000Z"),
                        historyEntry("st-100", "2026-08-01T08:00:00.000Z")));
        enqueueJson(deal);
        enqueuePipeline();

        Optional<Deal> fetched = repository.fetchDeal("D-50");

        assertTrue(fetched.isPresent());
        assertEquals("D-50", fetched.get().dealId());
        assertEquals(DealStage.CLOSED_WON, fetched.get().stage());
        assertEquals(Instant.parse("2026-08-20T10:00:00Z"), fetched.get().stageChangedAt());
    }

    @Test
    void fetchDealReturnsEmptyOnA404WithoutFetchingThePipeline() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"error\",\"message\":\"deal not found\"}"));

        Optional<Deal> fetched = repository.fetchDeal("D-missing");

        assertEquals(Optional.empty(), fetched);
        assertEquals(2, server.getRequestCount(),
                "constructor pipeline + the 404 deal lookup - no pipeline re-fetch needed when the deal isn't found");
    }

    @Test
    void fetchDealRetriesOnA429ThenSucceeds() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "0")
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"error\",\"message\":\"rate limited\"}"));
        String deal = dealJson("D-51", "Survives a 429", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        enqueueJson(deal);
        enqueuePipeline();

        Optional<Deal> fetched = repository.fetchDeal("D-51");

        assertTrue(fetched.isPresent());
        assertEquals("D-51", fetched.get().dealId());
    }

    @Test
    void fetchDealRetriesOnA503ThenSucceeds() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        server.enqueue(new MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"error\",\"message\":\"service unavailable\"}"));
        String deal = dealJson("D-55", "Survives a 503", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        enqueueJson(deal);
        enqueuePipeline();

        Optional<Deal> fetched = repository.fetchDeal("D-55");

        assertTrue(fetched.isPresent());
        assertEquals("D-55", fetched.get().dealId());
    }

    @Test
    void fetchDealFailsFatallyOnA401WithoutTheTokenInTheMessage() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"error\",\"message\":\"Authentication credentials not found in request\","
                        + "\"category\":\"EXPIRED_AUTHENTICATION\"}"));

        HubSpotClientException exception =
                assertThrows(HubSpotClientException.class, () -> repository.fetchDeal("D-52"));

        assertTrue(exception.getMessage().contains("401"));
        assertTrue(exception.getMessage().toLowerCase().indexOf("test-token") < 0);
        assertEquals(2, server.getRequestCount(), "constructor pipeline + the one non-retried 401 lookup");
    }

    @Test
    void fetchDealIsAFreshRequestEveryCallNeverCached() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        String dealV1 = dealJson("D-53", "Drifts between fetches", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealV1);
        enqueuePipeline();
        Optional<Deal> first = repository.fetchDeal("D-53");
        assertEquals(DealStage.PROSPECT, first.orElseThrow().stage());

        String dealV2 = dealJson("D-53", "Drifts between fetches", "1000", null, "2026-08-01T08:00:00Z",
                String.join(",",
                        historyEntry("st-500", "2026-08-28T09:00:00.000Z"),
                        historyEntry("st-100", "2026-08-01T08:00:00.000Z")));
        enqueueJson(dealV2);
        enqueuePipeline();
        Optional<Deal> second = repository.fetchDeal("D-53");
        assertEquals(DealStage.CLOSED_WON, second.orElseThrow().stage());

        assertEquals(5, server.getRequestCount(),
                "constructor pipeline + (deal lookup + pipeline) for each of the two fetchDeal() calls - "
                        + "the second call could not have reflected the changed stage from a cached result");
    }

    @Test
    void fetchDealResultMatchesTheSameDealsEntryInSnapshotLatestPerDeal() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        String deal = dealJson("D-54", "Matches snapshot", "3000", "2026-09-10T00:00:00.000Z", "2026-07-01T08:00:00Z",
                String.join(",",
                        historyEntry("st-450", "2026-08-15T09:00:00.000Z"),
                        historyEntry("st-100", "2026-07-01T08:00:00.000Z")));

        enqueuePipeline();
        enqueueJson(dealsPage(null, deal));
        DealSnapshot snapshot = repository.snapshot();
        Deal fromSnapshot = snapshot.findLatestByDealId("D-54").orElseThrow();

        enqueueJson(deal);
        enqueuePipeline();
        Deal fromFetchDeal = repository.fetchDeal("D-54").orElseThrow();

        assertEquals(fromSnapshot, fromFetchDeal);
    }

    @Test
    void fetchDealRejectsBlankOrNullIdsWithoutMakingAnyRequest() {
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        assertThrows(IllegalArgumentException.class, () -> repository.fetchDeal(""));
        assertThrows(IllegalArgumentException.class, () -> repository.fetchDeal("   "));
        assertThrows(IllegalArgumentException.class, () -> repository.fetchDeal(null));

        assertEquals(1, server.getRequestCount(),
                "only the constructor's own pipeline fetch - validation fails before any deal lookup");
    }

    /** A {@link Clock} whose {@link #instant()} can be advanced between calls, for proving asOf moves. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant initial) {
            this.instant = initial;
        }

        void advanceTo(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
