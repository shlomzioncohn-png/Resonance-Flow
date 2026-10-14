package com.rnd.gateway;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for ManagerUsersController — task spec
 * manager-users-screen-task.md. Same pattern as GatewayRoutingTests
 * (MockWebServer standing in for BFF). Roles aren't checked by the Gateway
 * yet (Auth stage 2), so any valid token reaches BFF here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Gateway Manager Users routing")
class ManagerUsersControllerTest {

    private static final String MANAGER_AUTHORITY = "ROLE_MANAGER";

    private static MockWebServer mockBff;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void startMockBff() throws IOException {
        mockBff = new MockWebServer();
        mockBff.start();
    }

    @AfterAll
    static void stopMockBff() throws IOException {
        mockBff.shutdown();
    }

    @DynamicPropertySource
    static void registerBffUrl(DynamicPropertyRegistry registry) {
        registry.add("bff.service.url", () -> "http://localhost:" + mockBff.getPort());
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = mockBff.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).as("BFF should have received a request within 5s").isNotNull();
        return request;
    }

    // -------------------------------------------------------------------------
    // Authentication
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("No token returns 401, never reaches BFF")
    void noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/manager/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    @DisplayName("Valid token reaches BFF with the mock identity headers")
    void validToken_reachesBff() throws Exception {
        mockBff.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"items\":[],\"page\":0,\"size\":20,\"totalElements\":0,\"totalPages\":0}"));

        mockMvc.perform(get("/api/manager/users")
                        .with(jwt().authorities(new SimpleGrantedAuthority(MANAGER_AUTHORITY))))
                .andExpect(status().isOk());

        RecordedRequest recorded = takeRequest();
        assertThat(recorded.getPath()).startsWith("/manager/users");
        assertThat(recorded.getHeader("X-User-Id")).isEqualTo("mgr_001");
        assertThat(recorded.getHeader("X-User-Role")).isEqualTo("MANAGER");
    }

    // -------------------------------------------------------------------------
    // Edit allowlist — role/email/username never leave the Gateway
    // -------------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("PATCH edit strips fields outside the allowlist before forwarding to BFF")
    void updateUser_stripsDisallowedFields() throws Exception {
        mockBff.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"u1\",\"displayName\":\"Eli\",\"role\":\"EMPLOYEE\",\"status\":\"ACTIVE\",\"phone\":\"555-1234\"}"));

        mockMvc.perform(patch("/api/manager/users/u1")
                        .with(jwt().authorities(new SimpleGrantedAuthority(MANAGER_AUTHORITY)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"555-1234\",\"role\":\"SYSTEM_ADMIN\",\"email\":\"hacked@evil.com\"}"))
                .andExpect(status().isOk());

        RecordedRequest recorded = takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("555-1234");
        assertThat(body).doesNotContain("SYSTEM_ADMIN");
        assertThat(body).doesNotContain("hacked@evil.com");
    }

    // -------------------------------------------------------------------------
    // Status validation — PENDING isn't offered from this screen
    // -------------------------------------------------------------------------

    @Test
    @Order(15)
    @DisplayName("PATCH status with PENDING returns 400, never reaches BFF")
    void updateStatus_pending_returns400() throws Exception {
        mockMvc.perform(patch("/api/manager/users/u1/status")
                        .with(jwt().authorities(new SimpleGrantedAuthority(MANAGER_AUTHORITY)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isBadRequest());

        // mockBff is shared across the whole class, so getRequestCount() is
        // cumulative — assert no NEW request arrived instead of a global count.
        assertThat(mockBff.takeRequest(300, TimeUnit.MILLISECONDS))
                .as("Gateway must reject PENDING before ever calling BFF").isNull();
    }

    @Test
    @Order(16)
    @DisplayName("PATCH status with an unknown value returns 400")
    void updateStatus_unknownValue_returns400() throws Exception {
        mockMvc.perform(patch("/api/manager/users/u1/status")
                        .with(jwt().authorities(new SimpleGrantedAuthority(MANAGER_AUTHORITY)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NOT_A_STATUS\"}"))
                .andExpect(status().isBadRequest());

        assertThat(mockBff.takeRequest(300, TimeUnit.MILLISECONDS))
                .as("Gateway must reject an unknown status before ever calling BFF").isNull();
    }

    @Test
    @Order(17)
    @DisplayName("PATCH status with SUSPENDED forwards to BFF")
    void updateStatus_suspended_forwardsToBff() throws Exception {
        mockBff.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"u1\",\"displayName\":\"Eli\",\"role\":\"EMPLOYEE\",\"status\":\"SUSPENDED\"}"));

        mockMvc.perform(patch("/api/manager/users/u1/status")
                        .with(jwt().authorities(new SimpleGrantedAuthority(MANAGER_AUTHORITY)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        RecordedRequest recorded = takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/manager/users/u1/status");
    }

    // -------------------------------------------------------------------------
    // Downstream 409 passthrough — e.g. BFF's self-status-change rejection
    // -------------------------------------------------------------------------

    @Test
    @Order(20)
    @DisplayName("BFF 409 passes through with its own message")
    void bffConflict_passesThrough() throws Exception {
        mockBff.enqueue(new MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"ERROR\",\"errorCode\":\"CONFLICT\",\"message\":\"A manager cannot change their own status.\"}"));

        mockMvc.perform(patch("/api/manager/users/u1/status")
                        .with(jwt().authorities(new SimpleGrantedAuthority(MANAGER_AUTHORITY)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A manager cannot change their own status."));
    }
}
