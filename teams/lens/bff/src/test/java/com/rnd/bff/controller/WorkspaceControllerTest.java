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
import com.rnd.bff.client.EmployeeClient;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

// WorkspaceService and its three clients are real beans here (not mocked) -
// they're all plain mock-data classes with no actual network calls yet, so
// exercising them for real is cheap and verifies the actual response shape,
// not a stubbed stand-in.
@WebMvcTest(WorkspaceController.class)
@Import({WorkspaceService.class, InboxClient.class, ChainClient.class, MindClient.class, EmployeeClient.class})
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
    // InboxClient is a real singleton bean here (see class comment) and now
    // actually persists status changes (see InboxClient.updateStatus) instead
    // of rebuilding a fresh literal per call. Left uncleaned, task_001 would
    // stay IN_PROGRESS for every other test that reuses this cached context -
    // @DirtiesContext forces a fresh InboxClient (task_001 back to READY) for
    // whatever runs after this test.
    @DirtiesContext
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

    @Test
    void updateTaskStatus_unknownTaskId_returns404() throws Exception {
        TaskStatusUpdateDTO body = new TaskStatusUpdateDTO("IN_PROGRESS");

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
        mockMvc
                .perform(get("/workspace/tasks/task_002").header("X-User-Id", "emp_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("task_002")))
                .andExpect(jsonPath("$.title", is("Update customer proposal")));
    }

    @Test
    void getTask_unknownId_returns404() throws Exception {
        mockMvc
                .perform(get("/workspace/tasks/task_does_not_exist").header("X-User-Id", "emp_001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("NOT_FOUND")));
    }

    @Test
    void listTasks_noFilter_returnsAllTasksAndTotal() throws Exception {
        mockMvc
                .perform(get("/workspace/tasks").header("X-User-Id", "emp_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(6)))
                .andExpect(jsonPath("$.items.length()", is(6)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(20)));
    }

    @Test
    void listTasks_filterBlocked_returnsOnlyBlockedTasks() throws Exception {
        mockMvc
                .perform(get("/workspace/tasks?filter=blocked").header("X-User-Id", "emp_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(2)))
                .andExpect(jsonPath("$.items[0].status", is("BLOCKED")))
                .andExpect(jsonPath("$.items[1].status", is("BLOCKED")));
    }

    @Test
    void listTasks_search_matchesTitleCaseInsensitively() throws Exception {
        mockMvc
                .perform(get("/workspace/tasks?search=LOGIN").header("X-User-Id", "emp_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.items[0].id", is("task_005")));
    }

    @Test
    void listTasks_pagination_secondPageReturnsRemainder() throws Exception {
        mockMvc
                .perform(get("/workspace/tasks?page=1&size=4").header("X-User-Id", "emp_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(6)))
                .andExpect(jsonPath("$.items.length()", is(2)))
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

    @Test
    void getMyTask_returnsTheMockEmployeeTask() throws Exception {
        mockMvc
                .perform(get("/workspace/employee-task").header("X-User-Id", "emp_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("task-1")))
                .andExpect(jsonPath("$.title", is("Review API changes")))
                .andExpect(jsonPath("$.status", is("Blocked")))
                .andExpect(jsonPath("$.blockedBy[0].id", is("task-0")));
    }

    @Test
    void getMyTask_missingUserIdHeader_returns400() throws Exception {
        mockMvc
                .perform(get("/workspace/employee-task"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("BAD_REQUEST")));
    }
}
