package com.rnd.bridge.crm.repository.hubspot;

import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
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
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link HubSpotDealRepository} against a {@link MockWebServer}
 * with recorded-shape HubSpot response bodies. No test in this class talks
 * to the live HubSpot API.
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
        HubSpotClient client = new HubSpotClient(
                RestClient.builder(),
                server.url("/").toString(),
                "test-token",
                100,
                maxRetries,
                initialBackoffMillis,
                maxBackoffMillis,
                millis -> { });
        return new HubSpotDealRepository(client, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
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
        enqueuePipeline();
        String deal = dealJson("D-1", "Acme deal", "50000", "2026-09-15T00:00:00.000Z", "2026-05-01T08:00:00Z",
                String.join(",",
                        historyEntry("st-500", "2026-08-20T10:00:00.000Z"),
                        historyEntry("st-450", "2026-08-10T09:00:00.000Z"),
                        historyEntry("st-100", "2026-05-01T08:00:00.000Z")));
        enqueueJson(dealsPage(null, deal));

        HubSpotDealRepository repository = newRepository();
        List<Deal> history = repository.findHistoryByDealId("D-1");

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
        enqueuePipeline();
        String deal = dealJson("D-2", "Reopened deal", "10000", null, "2026-04-01T08:00:00Z",
                String.join(",",
                        historyEntry("st-400", "2026-08-15T09:00:00.000Z"),
                        historyEntry("st-500", "2026-08-05T09:00:00.000Z"),
                        historyEntry("st-200", "2026-04-01T08:00:00.000Z")));
        enqueueJson(dealsPage(null, deal));

        HubSpotDealRepository repository = newRepository();
        List<Deal> history = repository.findHistoryByDealId("D-2");

        assertEquals(
                List.of(DealStage.QUALIFIED, DealStage.CLOSED_WON, DealStage.NEGOTIATION),
                history.stream().map(Deal::stage).toList());

        Deal latest = repository.findLatestByDealId("D-2").orElseThrow();
        assertEquals(DealStage.NEGOTIATION, latest.stage());
        assertEquals(history.get(2), latest);
    }

    @Test
    void dealWithOneHistoryEntryProducesOneRecord() {
        enqueuePipeline();
        String deal = dealJson("D-3", "Single event deal", "5000", "2026-10-01T00:00:00.000Z",
                "2026-08-01T08:00:00Z", historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, deal));

        HubSpotDealRepository repository = newRepository();
        List<Deal> history = repository.findHistoryByDealId("D-3");

        assertEquals(1, history.size());
        assertEquals(DealStage.PROSPECT, history.get(0).stage());
    }

    @Test
    void dealWithNoCloseDateAndNoAmountRoundTripsAsNullsNotDefaults() {
        enqueuePipeline();
        String deal = dealJson("D-4", "No close date or amount", null, null, "2026-08-01T08:00:00Z",
                historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, deal));

        HubSpotDealRepository repository = newRepository();
        Deal only = repository.findLatestByDealId("D-4").orElseThrow();

        assertNull(only.closeDate());
        assertNull(only.dealValue());
    }

    @Test
    void stageIdsResolveThroughTheFetchedPipelineMappingNotAHardcodedTable() {
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

        HubSpotDealRepository repository = newRepository();
        List<Deal> history = repository.findHistoryByDealId("D-5");

        assertEquals(List.of(DealStage.PROSPECT, DealStage.CLOSED_WON),
                history.stream().map(Deal::stage).toList());
    }

    @Test
    void unknownStageIdReferencedByADealIsSkippedNotFailingTheWholeSync() {
        // Was "fails loudly naming the id" - AbstractIntakeLane.run()'s
        // per-record try/catch never sees this (read() runs entirely
        // outside it), so one deal with a bad stage id used to abort every
        // deal in the sync, not just itself. fetchSync() now catches this
        // per record instead.
        enqueuePipeline();
        String badDeal = dealJson("D-6", "Bad stage", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-999-does-not-exist", "2026-08-01T08:00:00.000Z"));
        String goodDeal = dealJson("D-6b", "Good deal alongside it", "2000", null, "2026-08-02T08:00:00Z",
                historyEntry("st-100", "2026-08-02T08:00:00.000Z"));
        enqueueJson(dealsPage(null, badDeal, goodDeal));

        HubSpotDealRepository repository = newRepository();

        List<Deal> deals = assertDoesNotThrow(repository::findAll);

        assertEquals(List.of("D-6b"), deals.stream().map(Deal::dealId).toList());
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
        enqueuePipeline();
        String dealOnPageOne = dealJson("D-7", "Page one deal", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        String dealOnPageTwo = dealJson("D-8", "Page two deal", "2000", null, "2026-08-02T08:00:00Z",
                historyEntry("st-200", "2026-08-02T08:00:00.000Z"));
        enqueueJson(dealsPage("cursor-2", dealOnPageOne));
        enqueueJson(dealsPage(null, dealOnPageTwo));

        HubSpotDealRepository repository = newRepository();
        List<String> dealIds = repository.findAllDealIds();

        assertEquals(List.of("D-7", "D-8"), dealIds);
        assertEquals(3, server.getRequestCount()); // pipeline + 2 deal pages

        RecordedRequest secondPageRequest = takeRequest(3); // pipeline, page 1, page 2
        assertTrue(secondPageRequest.getPath().contains("after=cursor-2"));
    }

    @Test
    void aTwoNineIsRetriedRatherThanPropagated() {
        enqueuePipeline();
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "0")
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"error\",\"message\":\"rate limited\"}"));
        String deal = dealJson("D-9", "Survives a 429", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, deal));

        HubSpotDealRepository repository = newRepository();
        List<Deal> all = repository.findAll();

        assertEquals(1, all.size());
        assertEquals("D-9", all.get(0).dealId());
    }

    @Test
    void exhaustingTheRetryBudgetOnRepeated429sFailsWithAClearError() {
        enqueuePipeline();
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse()
                    .setResponseCode(429)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{}"));
        }

        HubSpotDealRepository repository = newRepository(3, 0L, 0L);

        HubSpotClientException exception = assertThrows(HubSpotClientException.class, repository::findAll);
        assertTrue(exception.getMessage().contains("rate limit"));
        assertTrue(exception.getMessage().contains("/crm/v3/objects/deals"));
        assertTrue(exception.getMessage().toLowerCase().indexOf("test-token") < 0);
    }

    @Test
    void aFiveOhThreeIsRetriedRatherThanPropagated() {
        enqueuePipeline();
        server.enqueue(new MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"error\",\"message\":\"service unavailable\"}"));
        String deal = dealJson("D-11", "Survives a 503", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, deal));

        HubSpotDealRepository repository = newRepository();
        List<Deal> all = repository.findAll();

        assertEquals(1, all.size());
        assertEquals("D-11", all.get(0).dealId());
    }

    @Test
    void exhaustingTheRetryBudgetOnRepeatedFiveOhFoursFailsWithAClearError() {
        enqueuePipeline();
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse()
                    .setResponseCode(504)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{}"));
        }

        HubSpotDealRepository repository = newRepository(3, 0L, 0L);

        HubSpotClientException exception = assertThrows(HubSpotClientException.class, repository::findAll);
        assertTrue(exception.getMessage().contains("504"));
        assertTrue(exception.getMessage().contains("/crm/v3/objects/deals"));
        assertTrue(exception.getMessage().toLowerCase().indexOf("test-token") < 0);
    }

    @Test
    void aFatalFourOhFourFailsImmediatelyWithoutRetrying() {
        enqueuePipeline();
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"error\",\"message\":\"not found\"}"));

        HubSpotDealRepository repository = newRepository();

        HubSpotClientException exception = assertThrows(HubSpotClientException.class, repository::findAll);
        assertTrue(exception.getMessage().contains("404"));
        assertEquals(2, server.getRequestCount()); // pipeline + one non-retried deal request
    }

    @Test
    void asOfIsStableAcrossCallsWithinOneSync() {
        enqueuePipeline();
        String deal = dealJson("D-10", "Stable asOf", "1000", null, "2026-08-01T08:00:00Z",
                historyEntry("st-100", "2026-08-01T08:00:00.000Z"));
        enqueueJson(dealsPage(null, deal));

        HubSpotDealRepository repository = newRepository();

        Instant firstCall = repository.asOf();
        repository.findAll();
        Instant secondCall = repository.asOf();

        assertEquals(FIXED_NOW, firstCall);
        assertSame(firstCall, secondCall);
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
}
