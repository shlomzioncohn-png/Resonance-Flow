package com.rnd.bridge.crm.repository.salesforce;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link SalesforceClient} against a {@link MockWebServer} with recorded-shape
 * Salesforce response bodies (OAuth token responses, SOQL query pages). No test here talks to a
 * live Salesforce org. The same {@link MockWebServer} instance serves as both the login host and
 * the {@code instance_url} host - every token response's {@code instance_url} points right back
 * at it, same as a real org where both normally differ only in subdomain.
 */
class SalesforceClientTest {

    private static final String TEST_CLIENT_ID = "test-client-id";
    private static final String TEST_CLIENT_SECRET = "test-client-secret";
    private static final String TEST_ACCESS_TOKEN = "test-access-token";

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

    private SalesforceClient newClient() {
        return newClient(5, 0L, 0L);
    }

    private SalesforceClient newClient(int maxRetries, long initialBackoffMillis, long maxBackoffMillis) {
        return new SalesforceClient(
                RestClient.builder(),
                server.url("/").toString(),
                TEST_CLIENT_ID,
                TEST_CLIENT_SECRET,
                maxRetries,
                initialBackoffMillis,
                maxBackoffMillis,
                millis -> { });
    }

    private void enqueueJson(String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    private void enqueueStatus(int status, String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    private String tokenResponse() {
        return """
                {"access_token": "%s", "instance_url": "%s", "token_type": "Bearer"}
                """.formatted(TEST_ACCESS_TOKEN, server.url("/").toString());
    }

    private static String record(String id) {
        return "{\"Id\": \"" + id + "\"}";
    }

    private static String queryPage(String nextRecordsUrl, boolean done, String... recordsJson) {
        String next = nextRecordsUrl == null ? "null" : "\"" + nextRecordsUrl + "\"";
        return "{\"totalSize\": " + recordsJson.length + ", \"done\": " + done
                + ", \"nextRecordsUrl\": " + next + ", \"records\": [" + String.join(",", recordsJson) + "]}";
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

    @Test
    void authSucceedsAndQueryReturnsRecords() {
        enqueueJson(tokenResponse());
        enqueueJson(queryPage(null, true, record("006A")));

        List<Map<String, Object>> records = newClient().query("SELECT Id FROM Opportunity");

        assertEquals(1, records.size());
        assertEquals("006A", records.get(0).get("Id"));
        assertEquals(2, server.getRequestCount(), "one token request + one query page");
    }

    @Test
    void authFailureFailsFatallyWithoutRetrying() {
        enqueueStatus(400, "{\"error\": \"invalid_client\", \"error_description\": \"client identifier invalid\"}");

        SalesforceClient client = newClient();

        SalesforceClientException exception =
                assertThrows(SalesforceClientException.class, () -> client.query("SELECT Id FROM Opportunity"));
        assertTrue(exception.getMessage().contains("token request failed"));
        assertTrue(exception.getMessage().toLowerCase().indexOf(TEST_CLIENT_SECRET) < 0);
        assertEquals(1, server.getRequestCount(), "one token attempt, never retried");
    }

    @Test
    void cachedTokenIsReusedAcrossCalls() {
        enqueueJson(tokenResponse());
        enqueueJson(queryPage(null, true, record("006A")));
        enqueueJson(queryPage(null, true, record("006B")));

        SalesforceClient client = newClient();
        client.query("SELECT Id FROM Opportunity WHERE Id = '006A'");
        client.query("SELECT Id FROM Opportunity WHERE Id = '006B'");

        assertEquals(3, server.getRequestCount(), "one token request + two query calls - no second token request");
    }

    @Test
    void aFourOhOneTriggersExactlyOneRefreshThenSucceeds() {
        enqueueJson(tokenResponse());
        enqueueStatus(401, "{\"message\": \"Session expired or invalid\", \"errorCode\": \"INVALID_SESSION_ID\"}");
        enqueueJson(tokenResponse());
        enqueueJson(queryPage(null, true, record("006A")));

        List<Map<String, Object>> records = newClient().query("SELECT Id FROM Opportunity");

        assertEquals(1, records.size());
        assertEquals(4, server.getRequestCount(), "token, 401'd query, refreshed token, retried query");
    }

    @Test
    void aSecondFourOhOneAfterARefreshIsFatal() {
        enqueueJson(tokenResponse());
        enqueueStatus(401, "{\"errorCode\": \"INVALID_SESSION_ID\"}");
        enqueueJson(tokenResponse());
        enqueueStatus(401, "{\"errorCode\": \"INVALID_SESSION_ID\"}");

        SalesforceClient client = newClient();

        SalesforceClientException exception =
                assertThrows(SalesforceClientException.class, () -> client.query("SELECT Id FROM Opportunity"));
        assertTrue(exception.getMessage().contains("invalid session"));
        assertTrue(exception.getMessage().toLowerCase().indexOf(TEST_ACCESS_TOKEN.toLowerCase()) < 0);
        assertEquals(4, server.getRequestCount(), "token, first 401, refresh token, second 401 - never a third attempt");
    }

    @Test
    void paginationFollowsNextRecordsUrlAcrossBothPages() {
        enqueueJson(tokenResponse());
        enqueueJson(queryPage("/services/data/v68.0/query/01-cursor-2", false, record("006A")));
        enqueueJson(queryPage(null, true, record("006B")));

        List<Map<String, Object>> records = newClient().query("SELECT Id FROM Opportunity");

        assertEquals(List.of("006A", "006B"), records.stream().map(r -> r.get("Id")).toList());
        assertEquals(3, server.getRequestCount(), "token + 2 query pages");

        RecordedRequest secondPageRequest = takeRequest(3);
        assertTrue(secondPageRequest.getPath().contains("01-cursor-2"));
    }

    @Test
    void requestLimitExceededFailsImmediatelyWithoutRetrying() {
        enqueueJson(tokenResponse());
        enqueueStatus(403, "[{\"message\": \"TotalRequests Limit exceeded.\", \"errorCode\": \"REQUEST_LIMIT_EXCEEDED\"}]");

        SalesforceClient client = newClient(5, 0L, 0L);

        SalesforceClientException exception =
                assertThrows(SalesforceClientException.class, () -> client.query("SELECT Id FROM Opportunity"));
        assertTrue(exception.getMessage().contains("request limit exceeded"));
        assertEquals(2, server.getRequestCount(), "token + exactly one query attempt, never retried");
    }

    @Test
    void aFiveHundredIsRetriedRatherThanPropagated() {
        enqueueJson(tokenResponse());
        enqueueStatus(500, "{}");
        enqueueJson(queryPage(null, true, record("006A")));

        List<Map<String, Object>> records = newClient().query("SELECT Id FROM Opportunity");

        assertEquals(1, records.size());
    }

    @Test
    void aFiveOhThreeIsRetriedRatherThanPropagated() {
        enqueueJson(tokenResponse());
        enqueueStatus(503, "{}");
        enqueueJson(queryPage(null, true, record("006A")));

        List<Map<String, Object>> records = newClient().query("SELECT Id FROM Opportunity");

        assertEquals(1, records.size());
    }

    @Test
    void exhaustingTheRetryBudgetOnRepeatedFiveHundredsFailsWithAClearError() {
        enqueueJson(tokenResponse());
        for (int i = 0; i < 3; i++) {
            enqueueStatus(500, "{}");
        }

        SalesforceClient client = newClient(3, 0L, 0L);

        SalesforceClientException exception =
                assertThrows(SalesforceClientException.class, () -> client.query("SELECT Id FROM Opportunity"));
        assertTrue(exception.getMessage().contains("500"));
    }

    @Test
    void aFatalFourHundredFailsImmediatelyWithoutRetrying() {
        enqueueJson(tokenResponse());
        enqueueStatus(400, "[{\"message\": \"malformed query\", \"errorCode\": \"MALFORMED_QUERY\"}]");

        SalesforceClient client = newClient();

        SalesforceClientException exception =
                assertThrows(SalesforceClientException.class, () -> client.query("SELECT Id FROM Opportunity"));
        assertTrue(exception.getMessage().contains("400"));
        assertEquals(2, server.getRequestCount(), "token + one non-retried query attempt");
    }

    /**
     * Mirrors {@code HubSpotClientTest}'s equivalent - proves {@link SalesforceClient#production}
     * actually wires the configured read timeout into the {@code RestClient} it builds.
     * {@code assertTimeoutPreemptively} guards the test itself from hanging if that wiring is
     * ever lost.
     */
    @Test
    void aReadTimeoutIsRetriedRatherThanBlockingForever() {
        enqueueJson(tokenResponse());
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(queryPage(null, true, record("006A")))
                .setBodyDelay(2, TimeUnit.SECONDS));
        enqueueJson(queryPage(null, true, record("006A")));

        SalesforceClient client = SalesforceClient.production(RestClient.builder(), server.url("/").toString(),
                TEST_CLIENT_ID, TEST_CLIENT_SECRET, 3, 0L, 0L, 5000L, 100L);

        List<Map<String, Object>> records =
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> client.query("SELECT Id FROM Opportunity"));

        assertEquals(1, records.size());
    }

    @Test
    void neitherTheClientSecretNorTheAccessTokenEverAppearsInAnExceptionMessage() {
        enqueueJson(tokenResponse());
        enqueueStatus(403, "[{\"message\": \"TotalRequests Limit exceeded.\", \"errorCode\": \"REQUEST_LIMIT_EXCEEDED\"}]");

        SalesforceClient client = newClient();

        SalesforceClientException exception =
                assertThrows(SalesforceClientException.class, () -> client.query("SELECT Id FROM Opportunity"));
        String message = exception.getMessage().toLowerCase();
        assertTrue(message.indexOf(TEST_CLIENT_SECRET.toLowerCase()) < 0);
        assertTrue(message.indexOf(TEST_ACCESS_TOKEN.toLowerCase()) < 0);
    }
}
