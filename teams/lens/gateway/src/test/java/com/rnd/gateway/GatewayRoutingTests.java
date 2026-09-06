package com.rnd.gateway;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Gateway routing.
 *
 * Gateway has ONE route: /api/** → BFF (StripPrefix=1).
 * There are no per-service routes (no /api/bridge, /api/mind, etc.).
 * BFF owns all downstream communication.
 *
 * Uses MockWebServer to stand in for the BFF so tests are self-contained.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@DisplayName("Gateway Routing")
class GatewayRoutingTests {

    static MockWebServer mockBff;

    @Autowired
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() throws IOException {
        mockBff = new MockWebServer();
        mockBff.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockBff.shutdown();
    }

    @DynamicPropertySource
    static void registerBffUrl(DynamicPropertyRegistry registry) {
        // Point Gateway at the MockWebServer so tests don't need a real BFF
        registry.add("bff.service.url", () -> "http://localhost:" + mockBff.getPort());
    }

    // -------------------------------------------------------------------------
    // Routing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/workspace/recommend routes to BFF /workspace/recommend (StripPrefix=1)")
    void testWorkspaceRecommendRoutesToBff() throws InterruptedException {
        mockBff.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"task\":{\"id\":\"t1\",\"title\":\"Test task\",\"status\":\"READY\",\"priority\":\"HIGH\"}," +
                         "\"explanation\":\"Test\",\"fitsInTime\":true,\"isHighPriority\":true,\"hasNoDependencies\":true}"));

        webTestClient.post()
                .uri("/api/workspace/recommend")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"availableTime\": 45}")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = mockBff.takeRequest();
        // StripPrefix=1 removes /api — BFF must receive /workspace/recommend
        assertThat(recorded.getPath()).isEqualTo("/workspace/recommend");
        assertThat(recorded.getMethod()).isEqualTo("POST");
    }

    @Test
    @DisplayName("Gateway injects X-User-Id header before forwarding to BFF")
    void testXUserIdHeaderInjected() throws InterruptedException {
        mockBff.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"task\":{\"id\":\"t1\",\"title\":\"Test\",\"status\":\"READY\",\"priority\":\"HIGH\"}," +
                         "\"explanation\":\"Test\",\"fitsInTime\":true,\"isHighPriority\":true,\"hasNoDependencies\":true}"));

        webTestClient.post()
                .uri("/api/workspace/recommend")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"availableTime\": 45}")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = mockBff.takeRequest();
        // Gateway must set X-User-Id — frontend never sends userId directly
        assertThat(recorded.getHeader("X-User-Id")).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("Unknown route returns 404")
    void testUnknownRouteReturns404() {
        webTestClient.get()
                .uri("/api/unknown/endpoint")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Query parameters are forwarded to BFF unchanged")
    void testQueryParametersForwarded() throws InterruptedException {
        mockBff.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{}"));

        webTestClient.get()
                .uri("/api/workspace/recommend?debug=true&format=json")
                .exchange();

        RecordedRequest recorded = mockBff.takeRequest();
        assertThat(recorded.getPath()).contains("debug=true");
        assertThat(recorded.getPath()).contains("format=json");
    }

    // -------------------------------------------------------------------------
    // BFF unavailable
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("BFF unavailable returns 503 SERVICE_UNAVAILABLE")
    void testBffUnavailableReturns503() throws IOException {
        // Shut down MockWebServer to simulate BFF being unreachable
        mockBff.shutdown();

        webTestClient.post()
                .uri("/api/workspace/recommend")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"availableTime\": 45}")
                .exchange()
                .expectStatus().isEqualTo(503);
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Missing availableTime returns 400 BAD_REQUEST")
    void testMissingAvailableTimeReturns400() {
        webTestClient.post()
                .uri("/api/workspace/recommend")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("BAD_REQUEST")
                .jsonPath("$.details").isNotEmpty();
    }

    @Test
    @DisplayName("availableTime of zero returns 400 BAD_REQUEST")
    void testZeroAvailableTimeReturns400() {
        webTestClient.post()
                .uri("/api/workspace/recommend")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"availableTime\": 0}")
                .exchange()
                .expectStatus().isBadRequest();
    }
}
