package com.rnd.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for Gateway health endpoints.
 * Tests that /health and /health/ready endpoints work correctly.
 * Also verifies that BFF dependency status is included in responses.
 *
 * Uses MockMvc, not WebTestClient — this is a Servlet (spring-boot-starter-web)
 * app with no WebFlux/reactor dependency, so no WebTestClient bean is ever
 * autoconfigured here (see GATEWAY_CONTRACT.md — manual RestTemplate routing,
 * not Spring Cloud Gateway).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@DisplayName("Gateway Health Endpoints")
class GatewayHealthTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /health returns UP status")
    void testHealthEndpoint() throws Exception {
        mockMvc.perform(get("/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.service").value("gateway"))
            .andExpect(jsonPath("$.version").value("0.1.0"))
            .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("GET /health/ready returns ready status")
    void testHealthReadyEndpoint() throws Exception {
        mockMvc.perform(get("/health/ready"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.service").value("gateway"))
            .andExpect(jsonPath("$.ready").value(true))
            .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("Health endpoint response contains required fields")
    void testHealthResponseFormat() throws Exception {
        mockMvc.perform(get("/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").exists())
            .andExpect(jsonPath("$.status").exists())
            .andExpect(jsonPath("$.service").exists())
            .andExpect(jsonPath("$.version").exists())
            .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("Health endpoint does not include BFF dependency (liveness only)")
    void testHealthDoesNotCallBFF() throws Exception {
        mockMvc.perform(get("/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.service").value("gateway"))
            // /health is liveness-only — no downstream calls, no dependencies block
            .andExpect(jsonPath("$.dependencies").doesNotExist());
    }

    @Test
    @DisplayName("Readiness endpoint includes BFF dependency status")
    void testReadinessIncludesBFFStatus() throws Exception {
        mockMvc.perform(get("/health/ready"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dependencies").exists())
            .andExpect(jsonPath("$.dependencies.bff").exists())
            .andExpect(jsonPath("$.dependencies.bff_url").isNotEmpty())
            .andExpect(jsonPath("$.dependencies.bff_ready").isNotEmpty());
    }
}
