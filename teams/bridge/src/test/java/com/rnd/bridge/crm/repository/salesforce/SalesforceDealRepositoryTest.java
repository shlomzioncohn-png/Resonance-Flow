package com.rnd.bridge.crm.repository.salesforce;

import com.rnd.bridge.crm.mapper.DealToTaskMapper;
import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
import com.rnd.bridge.crm.repository.DealSnapshot;
import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.NoOpTaskEnricher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link SalesforceDealRepository} against a {@link MockWebServer} with recorded-shape
 * Salesforce response bodies (OAuth token responses, SOQL query pages). No test here talks to a
 * live Salesforce org.
 * <p>
 * Request-count bookkeeping: {@link SalesforceDealRepository#snapshot()} does a stage-metadata
 * query, then an Opportunities query (token acquisition piggybacks on whichever of the two is
 * first in a given test's server queue); {@link SalesforceDealRepository#fetchDeal(String)} does
 * the single-Opportunity query first and only queries stage metadata if that lookup found a
 * record - mirroring {@code HubSpotDealRepositoryTest}'s "a miss costs one request, not two" rule.
 */
class SalesforceDealRepositoryTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-30T12:00:00Z");
    private static final DealToTaskMapper TASK_MAPPER =
            new DealToTaskMapper("org-test-0000", new EnrichmentProperties(), new NoOpTaskEnricher());

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

    private SalesforceDealRepository newRepository() {
        return newRepositoryWithClock(Clock.fixed(FIXED_NOW, ZoneOffset.UTC), "LastStageChangeDate");
    }

    private SalesforceDealRepository newRepositoryWithField(String stageChangedAtField) {
        return newRepositoryWithClock(Clock.fixed(FIXED_NOW, ZoneOffset.UTC), stageChangedAtField);
    }

    private SalesforceDealRepository newRepositoryWithClock(Clock clock) {
        return newRepositoryWithClock(clock, "LastStageChangeDate");
    }

    private SalesforceDealRepository newRepositoryWithClock(Clock clock, String stageChangedAtField) {
        SalesforceClient client = new SalesforceClient(
                RestClient.builder(),
                server.url("/").toString(),
                "test-client-id",
                "test-client-secret",
                5,
                0L,
                0L,
                millis -> { });
        return new SalesforceDealRepository(
                client, clock, stageChangedAtField, false, new SalesforceStageMapping(20, 50, 80, 99));
    }

    private void enqueueJson(String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    private String tokenResponse() {
        return """
                {"access_token": "test-access-token", "instance_url": "%s", "token_type": "Bearer"}
                """.formatted(server.url("/").toString());
    }

    private static String queryPage(String... recordsJson) {
        return "{\"totalSize\": " + recordsJson.length + ", \"done\": true, \"nextRecordsUrl\": null, \"records\": ["
                + String.join(",", recordsJson) + "]}";
    }

    /** Stages matching {@link SalesforceStageMapping}'s default bands (20/50/80/99). */
    private static final String DEFAULT_STAGES = queryPage(
            stageRow("Qualification", false, false, 30),
            stageRow("Closed Won", true, true, 100),
            stageRow("Closed Lost", true, false, 0));

    private void enqueueDefaultStages() {
        enqueueJson(DEFAULT_STAGES);
    }

    private static String stageRow(String masterLabel, boolean isClosed, boolean isWon, Integer probability) {
        return "{\"MasterLabel\": \"" + masterLabel + "\", \"ApiName\": \"" + masterLabel.replace(" ", "_")
                + "\", \"IsActive\": true, \"IsClosed\": " + isClosed + ", \"IsWon\": " + isWon
                + ", \"DefaultProbability\": " + (probability == null ? "null" : probability) + "}";
    }

    private static String opportunityRow(
            String id, String name, String description, String stageName, String amount, String closeDate,
            String createdDate, String stageChangedAtField, String stageChangedAtValue,
            String ownerName, String ownerEmail) {
        String owner = (ownerName == null && ownerEmail == null)
                ? "null"
                : "{\"Name\": " + jsonString(ownerName) + ", \"Email\": " + jsonString(ownerEmail) + "}";
        return """
                {
                  "Id": "%s",
                  "Name": %s,
                  "Description": %s,
                  "StageName": %s,
                  "Amount": %s,
                  "CloseDate": %s,
                  "CreatedDate": %s,
                  "%s": %s,
                  "Owner": %s
                }
                """.formatted(
                id, jsonString(name), jsonString(description), jsonString(stageName),
                amount == null ? "null" : amount, jsonString(closeDate), jsonString(createdDate),
                stageChangedAtField, jsonString(stageChangedAtValue), owner);
    }

    private static String jsonString(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    @Test
    void snapshotMapsEveryFieldFromTheOpportunityRow() {
        enqueueJson(tokenResponse());
        enqueueDefaultStages();
        String opportunity = opportunityRow("006A", "Acme deal", "Big deal", "Qualification", "50000",
                "2026-09-15", "2026-05-01T08:00:00.000+0000", "LastStageChangeDate",
                "2026-08-20T10:00:00.000+0000", "Jane Doe", "jane@example.com");
        enqueueJson(queryPage(opportunity));

        Deal deal = newRepository().snapshot().findLatestByDealId("salesforce:006A").orElseThrow();

        assertEquals("salesforce:006A", deal.dealId());
        assertEquals("Acme deal", deal.title());
        assertEquals("Big deal", deal.description());
        assertEquals(DealStage.QUALIFIED, deal.stage());
        assertEquals("Jane Doe", deal.ownerName());
        assertEquals("jane@example.com", deal.ownerEmail());
        assertEquals(java.time.LocalDate.of(2026, 9, 15), deal.closeDate());
        assertEquals(new BigDecimal("50000"), deal.dealValue());
        assertNull(deal.currency());
        assertNull(deal.crmAccountSlug());
        assertEquals(server.url("/").toString() + "006A", deal.sourceUrl());
        assertEquals(Instant.parse("2026-05-01T08:00:00Z"), deal.createdAt());
        assertEquals(Instant.parse("2026-08-20T10:00:00Z"), deal.stageChangedAt());
    }

    @Test
    void sourceRefReadsCrmColonSalesforceColonId() {
        enqueueJson(tokenResponse());
        enqueueDefaultStages();
        String opportunity = opportunityRow("006B", "Deal", null, "Closed Won", "1000", null,
                "2026-05-01T08:00:00.000+0000", "LastStageChangeDate", "2026-08-20T10:00:00.000+0000", null, null);
        enqueueJson(queryPage(opportunity));

        Deal deal = newRepository().snapshot().findLatestByDealId("salesforce:006B").orElseThrow();

        assertEquals("crm:salesforce:006B", TASK_MAPPER.toSourceRef(deal));
    }

    @Test
    void closedAndWonAndClosedAndLostResolveCorrectly() {
        enqueueJson(tokenResponse());
        enqueueDefaultStages();
        String won = opportunityRow("006C", "Won deal", null, "Closed Won", "1000", null,
                "2026-05-01T08:00:00.000+0000", "LastStageChangeDate", "2026-08-20T10:00:00.000+0000", null, null);
        String lost = opportunityRow("006D", "Lost deal", null, "Closed Lost", "1000", null,
                "2026-05-01T08:00:00.000+0000", "LastStageChangeDate", "2026-08-20T10:00:00.000+0000", null, null);
        enqueueJson(queryPage(won, lost));

        DealSnapshot snapshot = newRepository().snapshot();

        assertEquals(DealStage.CLOSED_WON, snapshot.findLatestByDealId("salesforce:006C").orElseThrow().stage());
        assertEquals(DealStage.CLOSED_LOST, snapshot.findLatestByDealId("salesforce:006D").orElseThrow().stage());
    }

    @Test
    void noCloseDateAndNoAmountRoundTripAsNullsNotDefaults() {
        enqueueJson(tokenResponse());
        enqueueDefaultStages();
        String opportunity = opportunityRow("006E", "Sparse deal", null, "Qualification", null, null,
                "2026-05-01T08:00:00.000+0000", "LastStageChangeDate", "2026-08-20T10:00:00.000+0000", null, null);
        enqueueJson(queryPage(opportunity));

        Deal deal = newRepository().snapshot().findLatestByDealId("salesforce:006E").orElseThrow();

        assertNull(deal.closeDate());
        assertNull(deal.dealValue());
        assertNull(deal.ownerName());
        assertNull(deal.ownerEmail());
    }

    @Test
    void stageChangedAtIsReadFromTheConfiguredField() {
        enqueueJson(tokenResponse());
        enqueueDefaultStages();
        String opportunity = opportunityRow("006F", "Deal", null, "Qualification", "1000", null,
                "2026-05-01T08:00:00.000+0000", "LastModifiedDate", "2026-08-25T11:00:00.000+0000", null, null);
        enqueueJson(queryPage(opportunity));

        Deal deal = newRepositoryWithField("LastModifiedDate").snapshot()
                .findLatestByDealId("salesforce:006F").orElseThrow();

        assertEquals(Instant.parse("2026-08-25T11:00:00Z"), deal.stageChangedAt());
    }

    @Test
    void anOpportunityWithAnUnresolvableStageNameIsSkippedNotFailingTheWholeSync() {
        enqueueJson(tokenResponse());
        enqueueDefaultStages();
        String badOpportunity = opportunityRow("006G", "Bad stage", null, "Some Deleted Custom Stage", "1000",
                null, "2026-05-01T08:00:00.000+0000", "LastStageChangeDate", "2026-08-20T10:00:00.000+0000",
                null, null);
        String goodOpportunity = opportunityRow("006H", "Good deal", null, "Qualification", "2000", null,
                "2026-05-02T08:00:00.000+0000", "LastStageChangeDate", "2026-08-21T10:00:00.000+0000", null, null);
        enqueueJson(queryPage(badOpportunity, goodOpportunity));

        DealSnapshot snapshot = assertDoesNotThrow(() -> newRepository().snapshot());

        assertEquals(List.of("salesforce:006H"), snapshot.dealIds());
        assertEquals(1, snapshot.skippedAtRead());
    }

    @Test
    void latestPerDealReturnsOneEntryPerOpportunity() {
        enqueueJson(tokenResponse());
        enqueueDefaultStages();
        String first = opportunityRow("006I", "Deal one", null, "Qualification", "1000", null,
                "2026-05-01T08:00:00.000+0000", "LastStageChangeDate", "2026-08-20T10:00:00.000+0000", null, null);
        String second = opportunityRow("006J", "Deal two", null, "Closed Won", "2000", null,
                "2026-05-02T08:00:00.000+0000", "LastStageChangeDate", "2026-08-21T10:00:00.000+0000", null, null);
        enqueueJson(queryPage(first, second));

        DealSnapshot snapshot = newRepository().snapshot();

        assertEquals(2, snapshot.latestPerDeal().size());
        assertEquals(DealStage.QUALIFIED,
                snapshot.findLatestByDealId("salesforce:006I").orElseThrow().stage());
        assertEquals(DealStage.CLOSED_WON,
                snapshot.findLatestByDealId("salesforce:006J").orElseThrow().stage());
    }

    @Test
    void asOfComesFromTheClockAtTheStartOfTheSnapshotsFetch() {
        enqueueJson(tokenResponse());
        enqueueDefaultStages();
        String opportunity = opportunityRow("006K", "Deal", null, "Qualification", "1000", null,
                "2026-05-01T08:00:00.000+0000", "LastStageChangeDate", "2026-08-20T10:00:00.000+0000", null, null);
        enqueueJson(queryPage(opportunity));

        assertEquals(FIXED_NOW, newRepository().snapshot().asOf());
    }

    @Test
    void asOfOfTheSecondSnapshotIsLaterWhenTheClockAdvances() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-30T12:00:00Z"));
        SalesforceDealRepository repository = newRepositoryWithClock(clock);

        enqueueJson(tokenResponse());
        enqueueDefaultStages();
        String opportunity = opportunityRow("006L", "Deal", null, "Qualification", "1000", null,
                "2026-05-01T08:00:00.000+0000", "LastStageChangeDate", "2026-08-20T10:00:00.000+0000", null, null);
        enqueueJson(queryPage(opportunity));
        Instant firstAsOf = repository.snapshot().asOf();

        clock.advanceTo(Instant.parse("2026-09-05T08:00:00Z"));
        enqueueDefaultStages();
        enqueueJson(queryPage(opportunity));
        Instant secondAsOf = repository.snapshot().asOf();

        assertEquals(Instant.parse("2026-08-30T12:00:00Z"), firstAsOf);
        assertEquals(Instant.parse("2026-09-05T08:00:00Z"), secondAsOf);
        assertTrue(secondAsOf.isAfter(firstAsOf));
    }

    @Test
    void secondSnapshotFetchesFreshDataAndReflectsAChangedStage() {
        SalesforceDealRepository repository = newRepository();

        enqueueJson(tokenResponse());
        enqueueDefaultStages();
        String dealV1 = opportunityRow("006M", "Drifts over time", null, "Qualification", "1000", null,
                "2026-05-01T08:00:00.000+0000", "LastStageChangeDate", "2026-08-01T08:00:00.000+0000", null, null);
        enqueueJson(queryPage(dealV1));
        DealSnapshot first = repository.snapshot();
        assertEquals(DealStage.QUALIFIED, first.findLatestByDealId("salesforce:006M").orElseThrow().stage());

        enqueueDefaultStages();
        String dealV2 = opportunityRow("006M", "Drifts over time", null, "Closed Won", "1000", null,
                "2026-05-01T08:00:00.000+0000", "LastStageChangeDate", "2026-08-28T09:00:00.000+0000", null, null);
        enqueueJson(queryPage(dealV2));
        DealSnapshot second = repository.snapshot();

        assertEquals(DealStage.CLOSED_WON, second.findLatestByDealId("salesforce:006M").orElseThrow().stage());
        assertEquals(5, server.getRequestCount(),
                "token + (stages + opportunities) for each of the two snapshot() calls");
    }

    // ---- fetchDeal(String): a fresh, single-record lookup, never cached ----

    @Test
    void fetchDealReturnsTheMatchingDealWhenFound() {
        String opportunity = opportunityRow("006N", "Fetched directly", null, "Closed Won", "7000",
                "2026-09-01", "2026-08-01T08:00:00.000+0000", "LastStageChangeDate",
                "2026-08-20T10:00:00.000+0000", null, null);
        enqueueJson(tokenResponse());
        enqueueJson(queryPage(opportunity));
        enqueueDefaultStages();

        Optional<Deal> fetched = newRepository().fetchDeal("006N");

        assertTrue(fetched.isPresent());
        assertEquals("salesforce:006N", fetched.get().dealId());
        assertEquals(DealStage.CLOSED_WON, fetched.get().stage());
    }

    @Test
    void fetchDealReturnsEmptyOnZeroRecordsWithoutFetchingStageMetadata() {
        enqueueJson(tokenResponse());
        enqueueJson(queryPage());

        Optional<Deal> fetched = newRepository().fetchDeal("006-missing");

        assertEquals(Optional.empty(), fetched);
        assertEquals(2, server.getRequestCount(), "token + the zero-record query - no stage-metadata fetch needed");
    }

    @Test
    void fetchDealIsAFreshRequestEveryCallNeverCached() {
        SalesforceDealRepository repository = newRepository();

        String dealV1 = opportunityRow("006O", "Drifts between fetches", null, "Qualification", "1000", null,
                "2026-08-01T08:00:00.000+0000", "LastStageChangeDate", "2026-08-01T08:00:00.000+0000", null, null);
        enqueueJson(tokenResponse());
        enqueueJson(queryPage(dealV1));
        enqueueDefaultStages();
        Optional<Deal> first = repository.fetchDeal("006O");
        assertEquals(DealStage.QUALIFIED, first.orElseThrow().stage());

        String dealV2 = opportunityRow("006O", "Drifts between fetches", null, "Closed Won", "1000", null,
                "2026-08-01T08:00:00.000+0000", "LastStageChangeDate", "2026-08-28T09:00:00.000+0000", null, null);
        enqueueJson(queryPage(dealV2));
        enqueueDefaultStages();
        Optional<Deal> second = repository.fetchDeal("006O");
        assertEquals(DealStage.CLOSED_WON, second.orElseThrow().stage());

        assertEquals(5, server.getRequestCount(),
                "token + (opportunity + stages) for each of the two fetchDeal() calls - the second call could "
                        + "not have reflected the changed stage from a cached result");
    }

    @Test
    void fetchDealResultMatchesTheSameDealsEntryInSnapshotLatestPerDeal() {
        SalesforceDealRepository repository = newRepository();

        String opportunity = opportunityRow("006P", "Matches snapshot", "Desc", "Closed Won", "3000",
                "2026-09-10", "2026-07-01T08:00:00.000+0000", "LastStageChangeDate",
                "2026-08-15T09:00:00.000+0000", "Jane Doe", "jane@example.com");

        enqueueJson(tokenResponse());
        enqueueDefaultStages();
        enqueueJson(queryPage(opportunity));
        DealSnapshot snapshot = repository.snapshot();
        Deal fromSnapshot = snapshot.findLatestByDealId("salesforce:006P").orElseThrow();

        enqueueJson(queryPage(opportunity));
        enqueueDefaultStages();
        Deal fromFetchDeal = repository.fetchDeal("006P").orElseThrow();

        assertEquals(fromSnapshot, fromFetchDeal);
    }

    @Test
    void fetchDealRejectsBlankOrNullIdsWithoutMakingAnyRequest() {
        SalesforceDealRepository repository = newRepository();

        assertThrows(IllegalArgumentException.class, () -> repository.fetchDeal(""));
        assertThrows(IllegalArgumentException.class, () -> repository.fetchDeal("   "));
        assertThrows(IllegalArgumentException.class, () -> repository.fetchDeal(null));

        assertEquals(0, server.getRequestCount(), "validation fails before any request is made");
    }

    @Test
    void loadHistoryTrueFailsFastAtConstructionWithAClearMessage() {
        SalesforceClient client = new SalesforceClient(
                RestClient.builder(), server.url("/").toString(), "id", "secret", 5, 0L, 0L, millis -> { });

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new SalesforceDealRepository(
                        client, Clock.systemUTC(), "LastStageChangeDate", true, new SalesforceStageMapping(20, 50, 80, 99)));

        assertTrue(exception.getMessage().contains("load-history"));
        assertTrue(exception.getMessage().contains("not supported yet"));
        assertEquals(0, server.getRequestCount(), "fails before any request - this is a startup-time check");
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
