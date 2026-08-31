package com.rnd.bff.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bff.dto.WorkspaceRequestDTO;
import com.rnd.bff.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// WorkspaceService is a real bean here (not mocked) - it's a plain mock-data
// service with no external dependencies, so exercising it for real is cheap
// and verifies the actual response shape, not a stubbed stand-in.
@WebMvcTest(WorkspaceController.class)
@Import(WorkspaceService.class)
class WorkspaceControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void recommend_withValidRequestAndUserIdHeader_returnsRecommendation() throws Exception {
        WorkspaceRequestDTO request = new WorkspaceRequestDTO(45, null, null);

        mockMvc
                .perform(
                        post("/workspace/recommend")
                                .header("X-User-Id", "emp_001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.id", is("task_001")))
                .andExpect(jsonPath("$.fitsInTime", is(true)))
                .andExpect(jsonPath("$.isHighPriority", is(true)))
                .andExpect(jsonPath("$.hasNoDependencies", is(true)));
    }

    @Test
    void recommend_availableTimeShorterThanTask_reportsDoesNotFit() throws Exception {
        WorkspaceRequestDTO request = new WorkspaceRequestDTO(10, null, null);

        mockMvc
                .perform(
                        post("/workspace/recommend")
                                .header("X-User-Id", "emp_001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fitsInTime", is(false)))
                .andExpect(jsonPath("$.explanation", containsString("Does not fit")));
    }

    @Test
    void recommend_missingAvailableTime_returns400() throws Exception {
        WorkspaceRequestDTO request = new WorkspaceRequestDTO(null, null, null);

        mockMvc
                .perform(
                        post("/workspace/recommend")
                                .header("X-User-Id", "emp_001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("BAD_REQUEST")));
    }

    @Test
    void recommend_missingUserIdHeader_returns400() throws Exception {
        WorkspaceRequestDTO request = new WorkspaceRequestDTO(45, null, null);

        mockMvc
                .perform(
                        post("/workspace/recommend")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("BAD_REQUEST")));
    }
}
