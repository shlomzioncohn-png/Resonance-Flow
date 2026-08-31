package com.rnd.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.hamcrest.Matchers.*;

/**
 * Integration tests for Gateway health endpoints.
 * Tests that /health and /health/ready endpoints work correctly.
 * Also verifies that BFF dependency status is included in responses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@DisplayName("Gateway Health Endpoints")
class GatewayHealthTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("GET /health returns UP status")
    void testHealthEndpoint() {
        webTestClient
            .get()
            .uri("/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP")
            .jsonPath("$.service").isEqualTo("gateway")
            .jsonPath("$.version").isEqualTo("0.1.0")
            .jsonPath("$.timestamp").isNotEmpty();
    }

    @Test
    @DisplayName("GET /health/ready returns ready status")
    void testHealthReadyEndpoint() {
        webTestClient
            .get()
            .uri("/health/ready")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP")
            .jsonPath("$.service").isEqualTo("gateway")
            .jsonPath("$.ready").isEqualTo(true)
            .jsonPath("$.timestamp").isNotEmpty();
    }

    @Test
    @DisplayName("Health endpoint response contains required fields")
    void testHealthResponseFormat() {
        webTestClient
            .get()
            .uri("/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$").exists()
            .jsonPath("$.status").exists()
            .jsonPath("$.service").exists()
            .jsonPath("$.version").exists()
            .jsonPath("$.timestamp").exists();
    }
    
    @Test
    @DisplayName("Health endpoint includes BFF dependency status")
    void testHealthIncludesBFFStatus() {
        webTestClient
            .get()
            .uri("/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.dependencies").exists()
            .jsonPath("$.dependencies.bff").exists()
            .jsonPath("$.dependencies.bff_url").isEqualTo("http://bff-service:8080/health");
    }
    
    @Test
    @DisplayName("Readiness endpoint includes BFF dependency status")
    void testReadinessIncludesBFFStatus() {
        webTestClient
            .get()
            .uri("/health/ready")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.dependencies").exists()
            .jsonPath("$.dependencies.bff").exists()
            .jsonPath("$.dependencies.bff_ready").isNotEmpty();
    }
}
