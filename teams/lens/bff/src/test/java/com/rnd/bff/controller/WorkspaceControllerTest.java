package com.rnd.bff.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bff.client.ChainClient;
import com.rnd.bff.client.InboxClient;
import com.rnd.bff.client.MindClient;
import com.rnd.bff.dto.TaskStatusUpdateDTO;
import com.rnd.bff.dto.WorkspaceRequestDTO;
import com.rnd.bff.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// WorkspaceService and its three clients are real beans here (not mocked) -
// they're all plain mock-data classes with no actual network calls yet, so
// exercising them for real is cheap and verifies the actual response shape,
// not a stubbed stand-in.
@WebMvcTest(WorkspaceController.class)
@Import({WorkspaceService.class, InboxClient.class, ChainClient.class, MindClient.class})
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

    @Test
    void updateTaskStatus_emptyBody_returns400InsteadOfSilentlyDroppingStatus() throws Exception {
        mockMvc
                .perform(
                        patch("/workspace/tasks/task_001/status")
                                .header("X-User-Id", "emp_001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("BAD_REQUEST")));
    }

    @Test
    void updateTaskStatus_undocumentedEnumValue_returns400() throws Exception {
        TaskStatusUpdateDTO body = new TaskStatusUpdateDTO("DELETED_BY_ME");

        mockMvc
                .perform(
                        patch("/workspace/tasks/task_001/status")
                                .header("X-User-Id", "emp_001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("BAD_REQUEST")));
    }

    @Test
    void updateTaskStatus_documentedEnumValue_returns200AndEchoesStatus() throws Exception {
        TaskStatusUpdateDTO body = new TaskStatusUpdateDTO("IN_PROGRESS");

        mockMvc
                .perform(
                        patch("/workspace/tasks/task_001/status")
                                .header("X-User-Id", "emp_001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("IN_PROGRESS")));
    }
}
