package com.rnd.gateway;

import com.rnd.gateway.dto.TaskListResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the app.auth.enabled=false switch: /api/** is open, and the
 * Authorization header is ignored entirely — so the frontend keeps working
 * before it sends Keycloak tokens (Auth stage 3).
 */
@SpringBootTest(properties = "app.auth.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Gateway with auth disabled")
class AuthDisabledTests {

    private static final String TASKS_URL = "/api/workspace/tasks";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RestTemplate restTemplate;

    @BeforeEach
    void stubBff() {
        when(restTemplate.exchange(contains("workspace/tasks"), eq(HttpMethod.GET), any(),
                eq(TaskListResponseDTO.class)))
                .thenReturn(ResponseEntity.ok(new TaskListResponseDTO()));
    }

    @Test
    @DisplayName("No token → request is forwarded to BFF")
    void noTokenIsForwardedToBff() throws Exception {
        mockMvc.perform(get(TASKS_URL))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Invalid token → ignored, request is forwarded to BFF")
    void invalidTokenIsIgnored() throws Exception {
        mockMvc.perform(get(TASKS_URL).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isOk());
    }
}
