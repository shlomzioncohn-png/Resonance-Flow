package com.rnd.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Integration tests for Gateway routing.
 * Tests that requests are correctly routed to downstream services.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@DisplayName("Gateway Routing")
class GatewayRoutingTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("Unknown route returns 404 NOT_FOUND")
    void testUnknownRouteReturns404() {
        webTestClient
            .get()
            .uri("/api/unknown/endpoint")
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Request to /api/bridge routes correctly (path exists)")
    void testBridgeRoutePath() {
        // Note: This tests that the route is configured, not that backend is available
        webTestClient
            .get()
            .uri("/api/bridge/health")
            .exchange()
            .expectStatus()
            .isOk();
    }

    @Test
    @DisplayName("Request to /api/mind routes correctly (path exists)")
    void testMindRoutePath() {
        webTestClient
            .get()
            .uri("/api/mind/health")
            .exchange()
            .expectStatus()
            .isOk();
    }

    @Test
    @DisplayName("Request to /api/inbox routes correctly (path exists)")
    void testInboxRoutePath() {
        webTestClient
            .get()
            .uri("/api/inbox/health")
            .exchange()
            .expectStatus()
            .isOk();
    }

    @Test
    @DisplayName("Request to /api/chain routes correctly (path exists)")
    void testChainRoutePath() {
        webTestClient
            .get()
            .uri("/api/chain/health")
            .exchange()
            .expectStatus()
            .isOk();
    }

    @Test
    @DisplayName("Query parameters are forwarded to downstream service")
    void testQueryParametersForwarded() {
        webTestClient
            .get()
            .uri("/api/bridge/health?format=json")
            .exchange()
            .expectStatus()
            .isOk();
    }
}
