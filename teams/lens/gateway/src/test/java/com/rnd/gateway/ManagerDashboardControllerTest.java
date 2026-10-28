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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Same pattern as ManagerUsersControllerTest: MockWebServer stands in for BFF. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Gateway Manager Dashboard routing")
class ManagerDashboardControllerTest {

    private static final SimpleGrantedAuthority MANAGER = new SimpleGrantedAuthority("ROLE_MANAGER");

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

    @Test
    @Order(1)
    @DisplayName("No token returns 401, never reaches BFF")
    void noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/manager/dashboard/kpis")).andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    @DisplayName("Forwards to BFF with the mock identity and returns the KPIs, avgBlockedDays null")
    void forwardsAndReturnsKpis() throws Exception {
        mockBff.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"inProgress\":42,\"blocked\":9,\"delayed\":5,\"avgBlockedDays\":null}"));

        mockMvc.perform(get("/api/manager/dashboard/kpis").with(jwt().authorities(MANAGER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inProgress").value(42))
                .andExpect(jsonPath("$.blocked").value(9))
                .andExpect(jsonPath("$.delayed").value(5))
                .andExpect(content().json("{\"avgBlockedDays\": null}"));

        RecordedRequest recorded = takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/manager/dashboard/kpis");
        assertThat(recorded.getHeader("X-User-Id")).isEqualTo("mgr_001");
        assertThat(recorded.getHeader("X-User-Role")).isEqualTo("MANAGER");
    }

    @Test
    @Order(3)
    @DisplayName("period and project are forwarded as query params")
    void forwardsPeriodAndProject() throws Exception {
        mockBff.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"inProgress\":1,\"blocked\":0,\"delayed\":0,\"avgBlockedDays\":null}"));

        mockMvc.perform(get("/api/manager/dashboard/kpis")
                        .param("period", "30d")
                        .param("project", "p-1")
                        .with(jwt().authorities(MANAGER)))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath()).isEqualTo("/manager/dashboard/kpis?period=30d&project=p-1");
    }

    @Test
    @Order(4)
    @DisplayName("BFF's 400 keeps its status and message")
    void bffBadRequest_passesThrough() throws Exception {
        mockBff.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"errorCode\":\"BAD_REQUEST\",\"message\":\"period must be one of 7d, 30d, 90d\"}"));

        mockMvc.perform(get("/api/manager/dashboard/kpis").param("period", "1y").with(jwt().authorities(MANAGER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("period must be one of 7d, 30d, 90d"));
        takeRequest();
    }

    @Test
    @Order(5)
    @DisplayName("Project task counts: forwards with the mock identity and returns the list")
    void forwardsAndReturnsProjectTaskCounts() throws Exception {
        mockBff.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[{\"projectId\":\"p-1\",\"project\":\"Alpha\",\"inProgress\":2,\"snoozed\":1,"
                        + "\"open\":3,\"done\":4,\"overdue\":1}]"));

        mockMvc.perform(get("/api/manager/dashboard/projects").with(jwt().authorities(MANAGER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].project").value("Alpha"))
                .andExpect(jsonPath("$[0].inProgress").value(2))
                .andExpect(jsonPath("$[0].overdue").value(1));

        RecordedRequest recorded = takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/manager/dashboard/projects");
        assertThat(recorded.getHeader("X-User-Id")).isEqualTo("mgr_001");
    }

    @Test
    @Order(7)
    @DisplayName("Tasks in progress and blocked/delayed: forwarded to their BFF paths")
    void forwardsTaskLists() throws Exception {
        for (String view : new String[] {"in-progress", "blocked-delayed"}) {
            mockBff.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("[{\"id\":\"t-1\",\"title\":\"Fix login\",\"status\":\"IN_PROGRESS\",\"isAvailable\":false}]"));

            mockMvc.perform(get("/api/manager/dashboard/tasks/" + view).with(jwt().authorities(MANAGER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].title").value("Fix login"))
                    .andExpect(jsonPath("$[0].isAvailable").value(false));

            RecordedRequest recorded = takeRequest();
            assertThat(recorded.getPath()).isEqualTo("/manager/dashboard/tasks/" + view);
            assertThat(recorded.getHeader("X-User-Id")).isEqualTo("mgr_001");
        }
    }

    @Test
    @Order(6)
    @DisplayName("Project task counts: no token returns 401")
    void projectTaskCounts_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/manager/dashboard/projects")).andExpect(status().isUnauthorized());
    }
}
