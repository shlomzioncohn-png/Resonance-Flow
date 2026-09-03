package com.rnd.bridge.crm.repository.hubspot;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link HubSpotClient}'s transport-failure retry directly with a
 * {@link ClientHttpRequestFactory} that fails on demand, rather than through
 * a real socket disconnect against {@link MockWebServer}
 * ({@link HubSpotDealRepositoryTest} covers the 429/502/503/504/fatal-status
 * cases that way). A dropped connection isn't reliable for this: the
 * JDK {@code HttpClient} that {@code RestClient.builder()} resolves to by
 * default already retries a failed idempotent GET once on a fresh
 * connection before {@link HubSpotClient} ever sees a failure, so a
 * mock-server disconnect doesn't deterministically reach this class's own
 * retry logic.
 */
class HubSpotClientTest {

    private static final String EMPTY_PIPELINES = "{\"results\": []}";

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

    /** Throws a transport IOException the first {@code failuresBeforeSuccess} calls, then delegates for real. */
    private HubSpotClient clientFailingTransportThenDelegating(int failuresBeforeSuccess, int maxRetries) {
        ClientHttpRequestFactory real = new SimpleClientHttpRequestFactory();
        AtomicInteger remaining = new AtomicInteger(failuresBeforeSuccess);
        ClientHttpRequestFactory flaky = (uri, httpMethod) -> {
            if (remaining.getAndDecrement() > 0) {
                throw new IOException("simulated transport failure");
            }
            return real.createRequest(uri, httpMethod);
        };
        return new HubSpotClient(RestClient.builder().requestFactory(flaky), server.url("/").toString(),
                "test-token", 100, maxRetries, 0L, 0L, millis -> { });
    }

    @Test
    void aTransportFailureIsRetriedRatherThanPropagated() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(EMPTY_PIPELINES));

        HubSpotClient client = clientFailingTransportThenDelegating(2, 5);
        List<HubSpotPipeline> pipelines = client.fetchPipelines();

        assertEquals(List.of(), pipelines);
    }

    @Test
    void exhaustingTheRetryBudgetOnRepeatedTransportFailuresFailsWithAClearError() {
        HubSpotClient client = clientFailingTransportThenDelegating(10, 3);

        HubSpotClientException exception = assertThrows(HubSpotClientException.class, client::fetchPipelines);
        assertTrue(exception.getMessage().contains("/crm/v3/pipelines/deals"));
        assertTrue(exception.getMessage().toLowerCase().indexOf("test-token") < 0);
    }
}
