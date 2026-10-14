package com.rnd.bff.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bff.client.ChainClient;
import com.rnd.bff.client.InboxClient;
import com.rnd.bff.client.MindClient;
import com.rnd.bff.config.CorsConfig;
import com.rnd.bff.config.SecurityConfig;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.dto.TaskStatusUpdateDTO;
import com.rnd.bff.dto.WorkspaceRequestDTO;
import com.rnd.bff.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

// SecurityConfig/CorsConfig are imported because @WebMvcTest doesn't load
// them on its own - without them Spring Security's default (deny everything,
// 401) applies instead of the BFF's real permit-all chain.
// WorkspaceService is mocked with @MockitoBean, and each test configures
// its behavior as needed.
@WebMvcTest(WorkspaceController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class WorkspaceControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    
    @MockBean private WorkspaceService workspaceService;

    @Test
    void recommend_withValidRequestAndUserIdHeader_returnsRecommendation() throws Exception {
        WorkspaceRequestDTO request = new WorkspaceRequestDTO(45, null, null);
        
        TaskDTO mockTask = TaskDTO.builder()
                .id("task_001")
                .title("Test Task")
                .status("NEW")
                .priority("HIGH")
                .effortEstimate(30)
                .isAvailable(true)
                .build();
        
        com.rnd.bff.dto.TaskRecommendationDTO recommendation = 
                com.rnd.bff.dto.TaskRecommendationDTO.builder()
                .task(mockTask)
                .fitsInTime(true)
                .isHighPriority(true)
                .hasNoDependencies(true)
                .confidenceScore(85)
                .explanation("Test recommendation")
                .alternatives(List.of())
                .build();
        
        when(workspaceService.recommend(any(WorkspaceRequestDTO.class), anyString()))
                .thenReturn(recommendation);

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
        
        com.rnd.bff.dto.TaskRecommendationDTO recommendation = 
                com.rnd.bff.dto.TaskRecommendationDTO.builder()
                .fitsInTime(false)
                .explanation("Does not fit in your 10 minutes")
                .isHighPriority(false)
                .hasNoDependencies(true)
                .confidenceScore(0)
                .alternatives(List.of())
                .build();
        
        when(workspaceService.recommend(any(WorkspaceRequestDTO.class), anyString()))
                .thenReturn(recommendation);

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
        
        when(workspaceService.recommend(any(WorkspaceRequestDTO.class), anyString()))
                .thenThrow(new IllegalArgumentException("availableTime cannot be null"));

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
        when(workspaceService.updateTaskStatus(anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("newStatus cannot be null"));

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
        TaskStatusUpdateDTO body = new TaskStatusUpdateDTO("DELETED_BY_ME", null);
        
        when(workspaceService.updateTaskStatus(anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("Invalid status: DELETED_BY_ME"));

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
    @DirtiesContext
    void updateTaskStatus_documentedEnumValue_returns200AndEchoesStatus() throws Exception {
        TaskStatusUpdateDTO body = new TaskStatusUpdateDTO("IN_PROGRESS", null);
        
        TaskDTO updatedTask = TaskDTO.builder()
                .id("task_001")
                .title("Test Task")
                .status("IN_PROGRESS")
                .priority("HIGH")
                .effortEstimate(30)
                .build();
        
        when(workspaceService.updateTaskStatus(anyString(), anyString(), anyString(), any()))
                .thenReturn(updatedTask);

        mockMvc
                .perform(
                        patch("/workspace/tasks/task_001/status")
                                .header("X-User-Id", "emp_001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("IN_PROGRESS")));
    }

    @Test
    void updateTaskStatus_unknownTaskId_returns404() throws Exception {
        TaskStatusUpdateDTO body = new TaskStatusUpdateDTO("IN_PROGRESS", null);
        
        when(workspaceService.updateTaskStatus(anyString(), anyString(), anyString(), any()))
                .thenThrow(new com.rnd.bff.exception.TaskNotFoundException("task_does_not_exist"));

        mockMvc
                .perform(
                        patch("/workspace/tasks/task_does_not_exist/status")
                                .header("X-User-Id", "emp_001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("NOT_FOUND")));
    }

    @Test
    void getTask_existingId_returnsTask() throws Exception {
        TaskDTO mockTask = TaskDTO.builder()
                .id("task_002")
                .title("Update customer proposal")
                .status("IN_REVIEW")
                .priority("HIGH")
                .effortEstimate(60)
                .isAvailable(false)
                .build();
        
        when(workspaceService.getTask(anyString(), anyString()))
                .thenReturn(mockTask);

        mockMvc
                .perform(get("/workspace/tasks/task_002").header("X-User-Id", "emp_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("task_002")))
                .andExpect(jsonPath("$.title", is("Update customer proposal")));
    }

    @Test
    void getTask_unknownId_returns404() throws Exception {
        when(workspaceService.getTask(anyString(), anyString()))
                .thenThrow(new com.rnd.bff.exception.TaskNotFoundException("task_does_not_exist"));

        mockMvc
                .perform(get("/workspace/tasks/task_does_not_exist").header("X-User-Id", "emp_001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("NOT_FOUND")));
    }

    @Test
    void listTasks_noFilter_returnsAllTasksAndTotal() throws Exception {
        com.rnd.bff.dto.TaskListResponseDTO response = com.rnd.bff.dto.TaskListResponseDTO.builder()
                .items(List.of(
                        TaskDTO.builder().id("task_001").status("NEW").build(),
                        TaskDTO.builder().id("task_002").status("IN_REVIEW").build(),
                        TaskDTO.builder().id("task_003").status("APPROVED").build(),
                        TaskDTO.builder().id("task_004").status("IN_PROGRESS").build(),
                        TaskDTO.builder().id("task_005").status("DONE").build(),
                        TaskDTO.builder().id("task_006").status("SNOOZED").build(),
                        TaskDTO.builder().id("task_007").status("REJECTED").build(),
                        TaskDTO.builder().id("task_008").status("CANCELLED").build()
                ))
                .total(8)
                .page(0)
                .size(20)
                .build();
        
        when(workspaceService.listTasks(anyString(), any(), any(), anyInt(), anyInt()))
                .thenReturn(response);

        mockMvc
                .perform(get("/workspace/tasks").header("X-User-Id", "emp_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(8)))
                .andExpect(jsonPath("$.items.length()", is(8)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(20)));
    }

    @Test
    void listTasks_filterInProgress_returnsOnlyInProgressTasks() throws Exception {
        com.rnd.bff.dto.TaskListResponseDTO response = com.rnd.bff.dto.TaskListResponseDTO.builder()
                .items(List.of(
                        TaskDTO.builder().id("task_004").status("IN_PROGRESS").build()
                ))
                .total(1)
                .page(0)
                .size(20)
                .build();
        
        when(workspaceService.listTasks(anyString(), any(), any(), anyInt(), anyInt()))
                .thenReturn(response);

        mockMvc
                .perform(get("/workspace/tasks?filter=in_progress").header("X-User-Id", "emp_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.items[0].status", is("IN_PROGRESS")));
    }

    @Test
    void listTasks_search_matchesTitleCaseInsensitively() throws Exception {
        com.rnd.bff.dto.TaskListResponseDTO response = com.rnd.bff.dto.TaskListResponseDTO.builder()
                .items(List.of(
                        TaskDTO.builder().id("task_005").title("Fix login bug").status("DONE").build()
                ))
                .total(1)
                .page(0)
                .size(20)
                .build();
        
        when(workspaceService.listTasks(anyString(), any(), any(), anyInt(), anyInt()))
                .thenReturn(response);

        mockMvc
                .perform(get("/workspace/tasks?search=LOGIN").header("X-User-Id", "emp_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.items[0].id", is("task_005")));
    }

    @Test
    void listTasks_pagination_secondPageReturnsRemainder() throws Exception {
        com.rnd.bff.dto.TaskListResponseDTO response = com.rnd.bff.dto.TaskListResponseDTO.builder()
                .items(List.of(
                        TaskDTO.builder().id("task_005").status("DONE").build(),
                        TaskDTO.builder().id("task_006").status("SNOOZED").build(),
                        TaskDTO.builder().id("task_007").status("REJECTED").build(),
                        TaskDTO.builder().id("task_008").status("CANCELLED").build()
                ))
                .total(8)
                .page(1)
                .size(4)
                .build();
        
        when(workspaceService.listTasks(anyString(), any(), any(), anyInt(), anyInt()))
                .thenReturn(response);

        mockMvc
                .perform(get("/workspace/tasks?page=1&size=4").header("X-User-Id", "emp_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(8)))
                .andExpect(jsonPath("$.items.length()", is(4)))
                .andExpect(jsonPath("$.page", is(1)))
                .andExpect(jsonPath("$.size", is(4)));
    }

    @Test
    void listTasks_missingUserIdHeader_returns400() throws Exception {
        mockMvc
                .perform(get("/workspace/tasks"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("BAD_REQUEST")));
    }

    // -------------------------------------------------------------------------
    // GET /workspace/tasks/selection (mock Inbox)
    // -------------------------------------------------------------------------

    @Test
    void selectTasks_returnsTasksThatFitTogether() throws Exception {
        com.rnd.bff.dto.TaskSelectionDTO response = com.rnd.bff.dto.TaskSelectionDTO.builder()
                .availableMinutes(90)
                .selectedTasks(List.of(
                        com.rnd.bff.dto.SelectedTaskDTO.builder()
                                .id("task_001")
                                .title("Task 1")
                                .effortEstimate(35)
                                .selectionScore(0.9)
                                .build(),
                        com.rnd.bff.dto.SelectedTaskDTO.builder()
                                .id("task_004")
                                .title("Task 4")
                                .effortEstimate(45)
                                .selectionScore(0.8)
                                .build()
                ))
                .totalEffortMinutes(80)
                .remainingMinutes(10)
                .usedFallback(false)
                .build();
        
        when(workspaceService.selectTasks(anyString(), anyInt()))
                .thenReturn(response);

        mockMvc
                .perform(get("/workspace/tasks/selection?availableMinutes=90").header("X-User-Id", "emp_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableMinutes", is(90)))
                .andExpect(jsonPath("$.selectedTasks.length()", is(2)))
                .andExpect(jsonPath("$.selectedTasks[0].id", is("task_001")))
                .andExpect(jsonPath("$.totalEffortMinutes", is(80)))
                .andExpect(jsonPath("$.usedFallback", is(false)));
    }

    @Test
    void selectTasks_minutesOutOfRange_returns400() throws Exception {
        when(workspaceService.selectTasks(anyString(), anyInt()))
                .thenThrow(new IllegalArgumentException("availableMinutes must be between 1 and 480"));

        mockMvc
                .perform(get("/workspace/tasks/selection?availableMinutes=0").header("X-User-Id", "emp_001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("BAD_REQUEST")))
                .andExpect(jsonPath("$.message", containsString("between 1 and 480")));
    }

    @Test
    void selectTasks_minutesMissing_returns400() throws Exception {
        when(workspaceService.selectTasks(anyString(), any()))
                .thenThrow(new IllegalArgumentException("availableMinutes cannot be null"));

        mockMvc
                .perform(get("/workspace/tasks/selection").header("X-User-Id", "emp_001"))
                .andExpect(status().isBadRequest());
    }
}
