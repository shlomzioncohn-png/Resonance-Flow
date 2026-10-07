package com.rnd.bridge.crm.repository.hubspot;

import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
import com.rnd.bridge.crm.repository.DealSnapshot;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link HubSpotDealRepository} against a {@link MockWebServer}
 * with recorded-shape HubSpot response bodies. No test in this class talks
 * to the live HubSpot API.
 * <p>
 * Every test now goes through {@link HubSpotDealRepository#snapshot()} - the old per-instance-cache
 * finder methods (and the cache field behind them) were removed entirely (CRM-lane audit finding
 * F1). Because {@code snapshot()} does one fully independent fetch - pipeline AND deals - on every
 * call, and the constructor still does its own one-off pipeline fetch purely to fail fast on an
 * unrecognized stage label, every test here enqueues the pipeline TWICE: once for the constructor,
 * once for the {@code snapshot()} call under test.
 */
class HubSpotDealRepositoryTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-30T12:00:00Z");

    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
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
        HubSpotClient client = new HubSpotClient(
                RestClient.builder(),
                server.url("/").toString(),
                "test-token",
                100,
                maxRetries,
                initialBackoffMillis,
                maxBackoffMillis,
                millis -> { });
        return new HubSpotDealRepository(client, clock);
    }

    private void enqueueJson(String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    /** Pipeline with stage ids that look nothing like HubSpot's default slugs. */
    private static final String DEFAULT_PIPELINE = """
            {
              "results": [
                {
                  "id": "pl-1",
                  "label": "Sales Pipeline",
                  "stages": [
                    {"id": "st-100", "label": "Appointment Scheduled"},
                    {"id": "st-200", "label": "Qualified To Buy"},
                    {"id": "st-300", "label": "Presentation Scheduled"},
                    {"id": "st-400", "label": "Decision Maker Bought-In"},
                    {"id": "st-450", "label": "Contract Sent"},
                    {"id": "st-500", "label": "Closed Won"},
                    {"id": "st-600", "label": "Closed Lost"}
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

        // Same labels as HubSpot's default pipeline, but with stage ids that
        // would not appear in any hardcoded id table.
        String pipelineWithUnusualIds = """
                {
                  "results": [
                    {
                      "id": "pl-custom",
                      "label": "Custom Pipeline",
                      "stages": [
                        {"id": "zzz-1", "label": "Appointment Scheduled"},
                        {"id": "zzz-6", "label": "Closed Won"}
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
        // Was "fails loudly naming the id" - AbstractIntakeLane.run()'s
        // per-record try/catch never sees this (read() runs entirely
        // outside it), so one deal with a bad stage id used to abort every
        // deal in the sync, not just itself. snapshot() now catches this
        // per record instead.
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

    @Test
    void pipelineStageWithAnUnrecognizedLabelFailsAtConstruction() {
        String pipelineWithCustomStage = """
                {
                  "results": [
                    {
                      "id": "pl-1",
                      "label": "Sales Pipeline",
                      "stages": [
                        {"id": "st-1", "label": "Some Custom Renamed Stage"}
                      ]
                    }
                  ]
                }
                """;
        enqueueJson(pipelineWithCustomStage);

        UnknownHubSpotStageException exception =
                assertThrows(UnknownHubSpotStageException.class, this::newRepository);
        assertTrue(exception.getMessage().contains("Some Custom Renamed Stage"));
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
    void secondSnapshotFailsEntirelyWhenTheRefetchedPipelineHasAnUnrecognizedStageLabel() {
        // A real behavior change from the F1 fix, not previously possible: the OLD/deprecated path
        // resolved stage mapping once, at construction, so a stage id missing from THAT mapping was
        // a per-deal skip (see unknownStageIdReferencedByADealIsSkippedNotFailingTheWholeSync).
        // snapshot() re-fetches and re-resolves the WHOLE pipeline on every call, so an unrecognized
        // LABEL anywhere in the pipeline - even one no deal currently uses - now fails the entire
        // snapshot before the deals endpoint is ever called. Known limitation for F6, documented in
        // CLAUDE.md. CrmBridgeController's @ExceptionHandler (see CrmBridgeControllerTest) and the
        // scheduler's existing catch(RuntimeException) + log are what keep this loud and clear
        // instead of a confusing failure.
        enqueueConstructorPipeline();
        HubSpotDealRepository repository = newRepository();

        String pipelineWithAnUnrecognizedLabel = """
                {
                  "results": [
                    {
                      "id": "pl-1",
                      "label": "Sales Pipeline",
                      "stages": [
                        {"id": "st-100", "label": "Appointment Scheduled"},
                        {"id": "st-999", "label": "Some Brand New Custom Stage"}
                      ]
                    }
                  ]
                }
                """;
        enqueueJson(pipelineWithAnUnrecognizedLabel);

        UnknownHubSpotStageException exception =
                assertThrows(UnknownHubSpotStageException.class, repository::snapshot);

        assertTrue(exception.getMessage().contains("Some Brand New Custom Stage"));
        assertEquals(2, server.getRequestCount(),
                "constructor pipeline + the failed snapshot pipeline fetch - never reaches the deals endpoint");
    }

    @Test
    void secondSnapshotPicksUpAStageIdAddedToThePipelineAfterTheFirstSnapshot() {
        // F6 is out of scope here (the mapping RULES don't change) - this is about F1/F14's
        // freshness: the old per-instance-cached path resolved stage ids once, at construction, and
        // never saw a stage id added later. snapshot() re-fetches the pipeline every call, so it
        // does.
        enqueueConstructorPipeline(); // constructor - only the seven original default-pipeline stages
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
                        {"id": "st-100", "label": "Appointment Scheduled"},
                        {"id": "st-700-new", "label": "Closed Won"}
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
