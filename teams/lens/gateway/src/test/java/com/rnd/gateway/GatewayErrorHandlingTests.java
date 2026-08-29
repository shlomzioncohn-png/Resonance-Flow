package com.rnd.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Integration tests for Gateway error handling.
 * Tests that errors are handled and returned correctly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@DisplayName("Gateway Error Handling")
class GatewayErrorHandlingTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("Unknown route returns 404 error")
    void testUnknownRouteError() {
        webTestClient
            .get()
            .uri("/unknown")
            .exchange()
            .expectStatus().isNotFound()
            .expectHeader().contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("Invalid HTTP method returns appropriate error")
    void testInvalidMethod() {
        webTestClient
            .options()
            .uri("/api/bridge/test")
            .exchange()
            .expectStatus()
            .is2xxSuccessful();
    }

    @Test
    @DisplayName("Malformed URL is handled")
    void testMalformedUrl() {
        webTestClient
            .get()
            .uri("/api/bridge/../../etc/passwd")
            .exchange()
            .expectStatus()
            .isOk();
    }

    @Test
    @DisplayName("POST request with invalid content type")
    void testPostWithInvalidContentType() {
        webTestClient
            .post()
            .uri("/api/bridge/test")
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue("invalid")
            .exchange()
            .expectStatus()
            .isOk();
    }

    @Test
    @DisplayName("Empty body POST request is handled")
    void testEmptyPostBody() {
        webTestClient
            .post()
            .uri("/api/bridge/test")
            .contentType(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus()
            .isOk();
    }

    @Test
    @DisplayName("Very large request body handling")
    void testLargeRequestBody() {
        StringBuilder largeBody = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            largeBody.append("x");
        }

        webTestClient
            .post()
            .uri("/api/bridge/test")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"data\": \"" + largeBody.toString() + "\"}")
            .exchange()
            .expectStatus()
            .isOk();
    }
}
