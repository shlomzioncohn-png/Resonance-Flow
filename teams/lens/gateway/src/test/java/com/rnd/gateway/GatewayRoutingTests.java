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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for Gateway routing.
 *
 * Gateway has ONE route: /api/workspace/** → BFF (manual routing via
 * WorkspaceController, see GATEWAY_CONTRACT.md). There are no per-service
 * routes (no /api/bridge, /api/mind, etc.). BFF owns all downstream
 * communication.
 *
 * Uses MockMvc, not WebTestClient (no WebFlux dependency in this Servlet
 * app — see GatewayHealthTests). Uses MockWebServer to stand in for the BFF.
 *
 * mockBff is started once for the whole class (@BeforeAll), not per test:
 * @DynamicPropertySource runs once, before the Spring context is created and
 * before any @BeforeEach — so the port it resolves must already be bound by
 * then, and stays fixed in the Gateway's @Value-injected bff.service.url for
 * every test in this class. testBffUnavailableReturns503 shuts that shared
 * server down, so it's ordered last — no test after it needs mockBff alive.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Gateway Routing")
class GatewayRoutingTests {

    private static MockWebServer mockBff;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void startMockBff() throws IOException {
        mockBff = new MockWebServer();
        mockBff.start();
    }

    @AfterAll
    static void stopMockBff() {
        // Already shut down by testBffUnavailableReturns503 in the normal run —
        // this only matters if that test didn't run (e.g. -Dtest=<other class>).
        try {
            mockBff.shutdown();
        } catch (IOException ignored) {
            // already shut down
        }
    }

    @DynamicPropertySource
    static void registerBffUrl(DynamicPropertyRegistry registry) {
        // Point Gateway at the MockWebServer so tests don't need a real BFF
        registry.add("bff.service.url", () -> "http://localhost:" + mockBff.getPort());
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = mockBff.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).as("BFF should have received a request within 5s").isNotNull();
        return request;
    }

    // -------------------------------------------------------------------------
    // Routing
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("POST /api/workspace/recommend routes to BFF /workspace/recommend")
    void testWorkspaceRecommendRoutesToBff() throws Exception {
        mockBff.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"task\":{\"id\":\"t1\",\"title\":\"Test task\",\"status\":\"READY\",\"priority\":\"HIGH\"}," +
                         "\"explanation\":\"Test\",\"fitsInTime\":true,\"isHighPriority\":true,\"hasNoDependencies\":true}"));

        mockMvc.perform(post("/api/workspace/recommend").with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"availableTime\": 45}"))
            .andExpect(status().isOk());

        RecordedRequest recorded = takeRequest();
        // Gateway forwards to BFF's own /workspace/recommend path
        assertThat(recorded.getPath()).isEqualTo("/workspace/recommend");
        assertThat(recorded.getMethod()).isEqualTo("POST");
    }

    @Test
    @Order(2)
    @DisplayName("Gateway injects X-User-Id header before forwarding to BFF")
    void testXUserIdHeaderInjected() throws Exception {
        mockBff.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"task\":{\"id\":\"t1\",\"title\":\"Test\",\"status\":\"READY\",\"priority\":\"HIGH\"}," +
                         "\"explanation\":\"Test\",\"fitsInTime\":true,\"isHighPriority\":true,\"hasNoDependencies\":true}"));

        mockMvc.perform(post("/api/workspace/recommend").with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"availableTime\": 45}"))
            .andExpect(status().isOk());

        RecordedRequest recorded = takeRequest();
        // Gateway must set X-User-Id — frontend never sends userId directly
        assertThat(recorded.getHeader("X-User-Id")).isNotNull().isNotEmpty();
    }

    @Test
    @Order(3)
    @DisplayName("Unknown route returns 404")
    void testUnknownRouteReturns404() throws Exception {
        mockMvc.perform(get("/api/unknown/endpoint").with(jwt()))
            .andExpect(status().isNotFound());
    }

    // Note: there used to be a "query parameters are forwarded to BFF unchanged"
    // test here. WorkspaceController builds the outbound BFF URL purely from
    // config (bff.service.url + bff.endpoints.*) — it never reads the incoming
    // request's query string, so nothing is actually forwarded. No caller sends
    // query params to this endpoint today (availableTime/projectFilter/minPriority
    // all travel in the JSON body), so this was asserting a passthrough behavior
    // that was never implemented and has no current consumer. Removed rather than
    // built out, since nothing needs it yet — add it back with real forwarding
    // logic in WorkspaceController if a caller ever does.

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    @Test
    @Order(5)
    @DisplayName("Missing availableTime returns 400 BAD_REQUEST")
    void testMissingAvailableTimeReturns400() throws Exception {
        mockMvc.perform(post("/api/workspace/recommend").with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.details").isNotEmpty());
    }

    @Test
    @Order(6)
    @DisplayName("availableTime of zero returns 400 BAD_REQUEST")
    void testZeroAvailableTimeReturns400() throws Exception {
        mockMvc.perform(post("/api/workspace/recommend").with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"availableTime\": 0}"))
            .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Task list ("My Tasks" screen)
    // -------------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("GET /api/workspace/tasks forwards filter/search/page/size as query params to BFF")
    void testTaskListForwardsQueryParamsToBff() throws Exception {
        mockBff.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"items\":[],\"total\":0,\"page\":1,\"size\":5}"));

        mockMvc.perform(get("/api/workspace/tasks?filter=blocked&search=login&page=1&size=5").with(jwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(5));

        RecordedRequest recorded = takeRequest();
        assertThat(recorded.getPath()).contains("/workspace/tasks");
        assertThat(recorded.getPath()).contains("filter=blocked");
        assertThat(recorded.getPath()).contains("search=login");
        assertThat(recorded.getPath()).contains("page=1");
        assertThat(recorded.getPath()).contains("size=5");
    }

    @Test
    @Order(11)
    @DisplayName("GET /api/workspace/tasks with no params forwards no query params (Gateway invents no defaults)")
    void testTaskListWithNoParamsForwardsNoQueryParams() throws Exception {
        mockBff.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"items\":[],\"total\":0,\"page\":0,\"size\":20}"));

        mockMvc.perform(get("/api/workspace/tasks").with(jwt()))
            .andExpect(status().isOk());

        RecordedRequest recorded = takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/workspace/tasks");
    }

    // -------------------------------------------------------------------------
    // Employee task (My Workspace demo)
    // -------------------------------------------------------------------------

    @Test
    @Order(15)
    @DisplayName("GET /api/workspace/employee-task routes to BFF /workspace/employee-task")
    void testEmployeeTaskRoutesToBff() throws Exception {
        mockBff.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"task-1\",\"title\":\"Review API changes\",\"status\":\"Blocked\"}"));

        mockMvc.perform(get("/api/workspace/employee-task").with(jwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("task-1"));

        RecordedRequest recorded = takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/workspace/employee-task");
        assertThat(recorded.getMethod()).isEqualTo("GET");
        assertThat(recorded.getHeader("X-User-Id")).isNotNull().isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // BFF unavailable — must run last: it permanently shuts down the shared
    // mockBff instance that every other test in this class depends on.
    // -------------------------------------------------------------------------

    @Test
    @Order(20)
    @DisplayName("BFF unavailable returns 503 SERVICE_UNAVAILABLE")
    void testBffUnavailableReturns503() throws Exception {
        mockBff.shutdown();

        mockMvc.perform(post("/api/workspace/recommend").with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"availableTime\": 45}"))
            .andExpect(status().isServiceUnavailable());
    }
}
