package com.rnd.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Gateway error handling.
 * Tests that errors are handled and returned correctly for real routes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@DisplayName("Gateway Error Handling")
class GatewayErrorHandlingTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Unknown route returns 404 error")
    void testUnknownRouteError() throws Exception {
        mockMvc
            .perform(get("/unknown"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("Authenticated workspace request passes security — fails downstream instead, on BFF being unreachable")
    void testWorkspaceEndpointAccess() throws Exception {
        // With a valid JWT the request gets past SecurityConfig. With no BFF running
        // in this test context, RestTemplate can't resolve bff-service, so the request
        // fails downstream as 503 SERVICE_UNAVAILABLE — not a 401/403 — which is
        // itself proof the auth layer let the authenticated request through.
        // (Unauthenticated requests are covered by SecurityJwtValidationTests.)
        mockMvc
            .perform(post("/api/workspace/recommend").with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"availableTime\": 45}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("Health endpoint returns 200")
    void testHealthEndpoint() throws Exception {
        mockMvc
            .perform(get("/health"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("Health ready endpoint returns 200 with BFF status")
    void testHealthReadyEndpoint() throws Exception {
        mockMvc
            .perform(get("/health/ready"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("Malformed JSON body returns 400 BAD_REQUEST")
    void testMalformedJsonBody() throws Exception {
        mockMvc
            .perform(post("/api/workspace/recommend").with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{invalid json}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("Missing required field returns 400 with details")
    void testMissingRequiredField() throws Exception {
        mockMvc
            .perform(post("/api/workspace/recommend").with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details").exists());
    }

    @Test
    @DisplayName("Invalid availableTime returns 400")
    void testInvalidAvailableTime() throws Exception {
        mockMvc
            .perform(post("/api/workspace/recommend").with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"availableTime\": 0}"))
            .andExpect(status().isBadRequest());
    }
}
