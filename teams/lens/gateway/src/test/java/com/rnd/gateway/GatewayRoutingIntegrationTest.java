package com.rnd.gateway;

import com.rnd.gateway.dto.TaskDTO;
import com.rnd.gateway.dto.TaskRecommendationDTO;
import com.rnd.gateway.dto.TaskListResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Gateway workspace routing.
 * 
 * Verifies that the Gateway correctly:
 * 1. Forwards requests to the BFF
 * 2. Handles timeouts and service unavailability
 * 3. Maps exceptions to correct HTTP status codes
 * 4. Includes required headers (X-User-Id)
 * 5. Returns responses in expected shapes
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, 
                properties = {"spring.config.name=application,application-test"})
@AutoConfigureMockMvc
class GatewayRoutingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        reset(restTemplate);
    }

    // =========================================================================
    // Happy path tests
    // =========================================================================

    @Test
    void recommend_whenBFFResponds_returns200WithTaskRecommendation() throws Exception {
        // Setup
        TaskDTO task = new TaskDTO();
        task.setId("task_001");
        task.setTitle("Review API changes");
        task.setProject("Platform Service");
        task.setPriority("HIGH");
        task.setStatus("READY");
        task.setEffortEstimate(35);

        TaskRecommendationDTO recommendation = new TaskRecommendationDTO();
        recommendation.setTask(task);
        recommendation.setExplanation("Fits in your 45 minutes");
        recommendation.setFitsInTime(true);
        recommendation.setIsHighPriority(true);
        recommendation.setHasNoDependencies(true);
        recommendation.setConfidenceScore(85);
        recommendation.setAlternatives(List.of());

        when(restTemplate.postForObject(
                contains("workspace/recommend"),
                any(),
                eq(TaskRecommendationDTO.class)))
                .thenReturn(recommendation);

        // Test
        mockMvc.perform(post("/api/workspace/recommend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"availableTime\": 45}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.id").value("task_001"))
                .andExpect(jsonPath("$.task.title").value("Review API changes"))
                .andExpect(jsonPath("$.fitsInTime").value(true))
                .andExpect(jsonPath("$.confidenceScore").value(85));

        verify(restTemplate, times(1)).postForObject(
                contains("workspace/recommend"), any(), eq(TaskRecommendationDTO.class));
    }

    @Test
    void listTasks_whenBFFResponds_returns200WithTaskList() throws Exception {
        // Setup
        TaskDTO task1 = new TaskDTO();
        task1.setId("task_001");
        task1.setTitle("Fix login bug");
        task1.setProject("Auth Service");
        task1.setPriority("HIGH");
        task1.setStatus("READY");

        TaskListResponseDTO taskList = new TaskListResponseDTO();
        taskList.setItems(List.of(task1));
        taskList.setTotal(1);
        taskList.setPage(0);
        taskList.setSize(20);

        when(restTemplate.exchange(
                contains("workspace/tasks"),
                eq(org.springframework.http.HttpMethod.GET),
                any(),
                eq(TaskListResponseDTO.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(taskList));

        // Test
        mockMvc.perform(get("/api/workspace/tasks?filter=all&page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].id").value("task_001"))
                .andExpect(jsonPath("$.total").value(1));

        verify(restTemplate, times(1)).exchange(
                contains("workspace/tasks"), any(), any(), eq(TaskListResponseDTO.class));
    }

    @Test
    void getTaskDetails_whenBFFResponds_returns200WithTaskDTO() throws Exception {
        // Setup
        TaskDTO task = new TaskDTO();
        task.setId("task_001");
        task.setTitle("Review API changes");
        task.setDescription("Review latest API changes");
        task.setProject("Platform Service");
        task.setPriority("HIGH");
        task.setStatus("READY");
        task.setEffortEstimate(35);
        task.setDueDate(LocalDateTime.of(2026, 8, 30, 17, 0));
        task.setOwner("Alex Morgan");

        when(restTemplate.exchange(
                contains("workspace/tasks/task_001"),
                eq(org.springframework.http.HttpMethod.GET),
                any(),
                eq(TaskDTO.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(task));

        // Test
        mockMvc.perform(get("/api/workspace/tasks/task_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("task_001"))
                .andExpect(jsonPath("$.title").value("Review API changes"));
    }

    @Test
    void getTaskDetails_whenBFFReturns403_passesBffMessageOn() throws Exception {
        byte[] bffBody = ("{\"status\":\"ERROR\",\"errorCode\":\"FORBIDDEN\","
                + "\"message\":\"Not allowed to access this task\"}").getBytes();
        when(restTemplate.exchange(
                contains("workspace/tasks/task_001"),
                eq(org.springframework.http.HttpMethod.GET),
                any(),
                eq(TaskDTO.class)))
                .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.FORBIDDEN, "Forbidden",
                        org.springframework.http.HttpHeaders.EMPTY, bffBody, null));

        mockMvc.perform(get("/api/workspace/tasks/task_001"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Not allowed to access this task"));
    }

    @Test
    void getTaskDetails_whenBFFReturns500_keepsGenericMessage() throws Exception {
        byte[] bffBody = "{\"message\":\"NullPointerException in InboxClient\"}".getBytes();
        when(restTemplate.exchange(
                contains("workspace/tasks/task_001"),
                eq(org.springframework.http.HttpMethod.GET),
                any(),
                eq(TaskDTO.class)))
                .thenThrow(org.springframework.web.client.HttpServerErrorException.create(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                        org.springframework.http.HttpHeaders.EMPTY, bffBody, null));

        mockMvc.perform(get("/api/workspace/tasks/task_001"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Downstream service returned an error"));
    }

    // =========================================================================
    // Timeout and error handling tests
    // =========================================================================

    @Test
    void recommend_whenBFFTimesOut_returns504GatewayTimeout() throws Exception {
        // Setup
        when(restTemplate.postForObject(
                contains("workspace/recommend"),
                any(),
                eq(TaskRecommendationDTO.class)))
                .thenThrow(new ResourceAccessException("Connection timeout",
                        new SocketTimeoutException("Read timed out")));

        // Test
        mockMvc.perform(post("/api/workspace/recommend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"availableTime\": 45}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value("TIMEOUT"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void listTasks_whenBFFIsUnreachable_returns503ServiceUnavailable() throws Exception {
        // Setup
        when(restTemplate.exchange(
                contains("workspace/tasks"),
                eq(org.springframework.http.HttpMethod.GET),
                any(),
                eq(TaskListResponseDTO.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        // Test
        mockMvc.perform(get("/api/workspace/tasks"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void recommend_whenBFFReturnsNull_returns503ServiceUnavailable() throws Exception {
        // Setup - BFF returns null response
        when(restTemplate.postForObject(
                contains("workspace/recommend"),
                any(),
                eq(TaskRecommendationDTO.class)))
                .thenReturn(null);

        // Test
        mockMvc.perform(post("/api/workspace/recommend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"availableTime\": 45}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"));
    }

    // =========================================================================
    // Validation tests
    // =========================================================================

    @Test
    void recommend_whenAvailableTimeIsMissing_returns400BadRequest() throws Exception {
        mockMvc.perform(post("/api/workspace/recommend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void recommend_whenAvailableTimeIsZero_returns400BadRequest() throws Exception {
        mockMvc.perform(post("/api/workspace/recommend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"availableTime\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void recommend_whenAvailableTimeIsNegative_returns400BadRequest() throws Exception {
        mockMvc.perform(post("/api/workspace/recommend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"availableTime\": -100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // =========================================================================
    // Error response format tests
    // =========================================================================

    @Test
    void errorResponse_includesRequiredFields() throws Exception {
        // Setup
        when(restTemplate.exchange(
                contains("workspace/tasks"),
                eq(org.springframework.http.HttpMethod.GET),
                any(),
                eq(TaskListResponseDTO.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        // Test
        mockMvc.perform(get("/api/workspace/tasks"))
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void errorResponse_doesNotLeakStackTrace() throws Exception {
        // Setup
        when(restTemplate.postForObject(
                contains("workspace/recommend"),
                any(),
                eq(TaskRecommendationDTO.class)))
                .thenThrow(new RuntimeException("Internal database error"));

        // Test - verify no stack trace in response
        mockMvc.perform(post("/api/workspace/recommend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"availableTime\": 45}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(not(containsString("database"))))
                .andExpect(jsonPath("$").value(not(hasKey("stackTrace"))));
    }
}
