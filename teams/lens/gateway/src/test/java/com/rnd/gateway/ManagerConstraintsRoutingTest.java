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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET/PATCH /api/manager/tasks/{taskId}/constraints[/{constraintId}] -> BFF, with
 * MockWebServer standing in for BFF (same pattern as ManagerDashboardControllerTest).
 * The PATCH must go through the same forward() as the GET: same headers, same error mapping.
 *
 * bffUnreachable_returns503 shuts the shared server down, so it's ordered last.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Gateway manager constraint routing")
class ManagerConstraintsRoutingTest {

    private static final String TASK = "7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c01";
    private static final String CONSTRAINT_JSON =
            "{\"constraintId\":\"c1\",\"type\":\"APPROVAL_NEEDED\",\"status\":\"%s\","
                    + "\"reason\":{\"approver\":\"Security team\"},\"sourceRef\":\"mind:run-42\","
                    + "\"createdAt\":\"2026-10-20T09:15:30\"}";

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
        try {
            mockBff.shutdown();
        } catch (IOException ignored) {
            // already shut down by bffUnreachable_returns503
        }
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

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static MockHttpServletRequestBuilder patchStatus(String constraintId, String status) {
        return patch("/api/manager/tasks/" + TASK + "/constraints/" + constraintId)
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}");
    }

    @Test
    @Order(1)
    @DisplayName("GET constraints is forwarded with the manager headers")
    void getConstraints_isForwarded() throws Exception {
        mockBff.enqueue(json(200, "[" + CONSTRAINT_JSON.formatted("PENDING") + "]"));

        mockMvc.perform(get("/api/manager/tasks/" + TASK + "/constraints").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].constraintId").value("c1"))
                .andExpect(jsonPath("$[0].reason.approver").value("Security team"))
                .andExpect(jsonPath("$[0].sourceRef").value("mind:run-42"));

        RecordedRequest recorded = takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("GET");
        assertThat(recorded.getPath()).isEqualTo("/manager/tasks/" + TASK + "/constraints");
        assertThat(recorded.getHeader("X-User-Id")).isEqualTo("mgr_001");
        assertThat(recorded.getHeader("X-User-Role")).isEqualTo("MANAGER");
    }

    @Test
    @Order(2)
    @DisplayName("PATCH is forwarded with its body, Content-Type and the manager headers")
    void patchStatus_isForwardedWithBodyAndHeaders() throws Exception {
        mockBff.enqueue(json(200, CONSTRAINT_JSON.formatted("SATISFIED")));

        mockMvc.perform(patchStatus("c1", "SATISFIED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.constraintId").value("c1"))
                .andExpect(jsonPath("$.status").value("SATISFIED"));

        RecordedRequest recorded = takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("PATCH");
        assertThat(recorded.getPath()).isEqualTo("/manager/tasks/" + TASK + "/constraints/c1");
        assertThat(recorded.getBody().readUtf8()).isEqualTo("{\"status\":\"SATISFIED\"}");
        assertThat(recorded.getHeader("Content-Type")).startsWith("application/json");
        assertThat(recorded.getHeader("X-User-Id")).isEqualTo("mgr_001");
        assertThat(recorded.getHeader("X-User-Role")).isEqualTo("MANAGER");
    }

    @Test
    @Order(3)
    @DisplayName("PATCH with a status that isn't PENDING/SATISFIED/WAIVED is 400 and never reaches BFF")
    void patchInvalidStatus_returns400() throws Exception {
        int before = mockBff.getRequestCount();

        mockMvc.perform(patchStatus("c1", "DONE")).andExpect(status().isBadRequest());

        assertThat(mockBff.getRequestCount()).isEqualTo(before);
    }

    @Test
    @Order(4)
    @DisplayName("BFF's 404 is passed on as 404, for GET and PATCH alike")
    void bffNotFound_returns404() throws Exception {
        String notFound = "{\"errorCode\":\"NOT_FOUND\",\"message\":\"Constraint not found\"}";
        mockBff.enqueue(json(404, notFound));
        mockBff.enqueue(json(404, notFound));

        mockMvc.perform(get("/api/manager/tasks/" + TASK + "/constraints").with(jwt()))
                .andExpect(status().isNotFound());
        takeRequest();

        mockMvc.perform(patchStatus("missing", "WAIVED")).andExpect(status().isNotFound());
        assertThat(takeRequest().getPath()).isEqualTo("/manager/tasks/" + TASK + "/constraints/missing");
    }

    @Test
    @Order(5)
    @DisplayName("BFF's 503 (Chain down) is passed on as 503, for GET and PATCH alike")
    void bffServiceUnavailable_returns503() throws Exception {
        String unavailable = "{\"errorCode\":\"SERVICE_UNAVAILABLE\",\"details\":\"Service: chain\"}";
        mockBff.enqueue(json(503, unavailable));
        mockBff.enqueue(json(503, unavailable));

        mockMvc.perform(get("/api/manager/tasks/" + TASK + "/constraints").with(jwt()))
                .andExpect(status().isServiceUnavailable());
        takeRequest();

        mockMvc.perform(patchStatus("c1", "PENDING")).andExpect(status().isServiceUnavailable());
        takeRequest();
    }

    @Test
    @Order(20)
    @DisplayName("PATCH with BFF unreachable is 503 SERVICE_UNAVAILABLE")
    void bffUnreachable_returns503() throws Exception {
        mockBff.shutdown();

        mockMvc.perform(patchStatus("c1", "SATISFIED"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"));
    }
}
