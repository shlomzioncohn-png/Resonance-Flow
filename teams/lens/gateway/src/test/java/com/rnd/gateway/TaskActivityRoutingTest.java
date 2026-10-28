package com.rnd.gateway;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/workspace/tasks/{taskId}/activity → BFF. MockWebServer stands in for BFF. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Gateway task activity routing")
class TaskActivityRoutingTest {

    private static final String TASK = "7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c01";

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

    @Test
    @DisplayName("Forwards to BFF with the user id and returns the activity as-is")
    void forwardsAndReturnsActivity() throws Exception {
        mockBff.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"items":[
                          {"id":"s1","type":"STATUS_CHANGED","createdAt":"2026-10-20T09:15:30Z","fromStatus":"PENDING","toStatus":"ACTIVE"},
                          {"id":"d1","type":"DEPENDENCY_ADDED","createdAt":"2026-10-18T11:40:00Z","dependsOnTaskId":"t-9","dependsOnTitle":"Write the API spec","source":"USER"}
                        ],"statusHistoryUnavailable":false,"dependencyHistoryUnavailable":true}
                        """));

        mockMvc.perform(get("/api/workspace/tasks/" + TASK + "/activity").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].fromStatus").value("PENDING"))
                .andExpect(jsonPath("$.items[0].dependsOnTitle").doesNotExist())
                .andExpect(jsonPath("$.items[1].dependsOnTitle").value("Write the API spec"))
                .andExpect(jsonPath("$.dependencyHistoryUnavailable").value(true));

        RecordedRequest recorded = mockBff.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getPath()).isEqualTo("/workspace/tasks/" + TASK + "/activity");
        assertThat(recorded.getHeader("X-User-Id")).isEqualTo("emp_001");
        // The tenant is the BFF's business, never the Gateway's.
        assertThat(recorded.getHeader("X-Tenant-Id")).isNull();
    }
}
