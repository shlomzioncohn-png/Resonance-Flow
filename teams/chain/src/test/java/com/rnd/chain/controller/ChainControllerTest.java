package com.rnd.chain.controller;

import com.rnd.chain.dto.TaskResponse;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTraceResponse;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.service.TaskTraversalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests for ChainController, matching Riki-Kaplan's ChainControllerTest style
 * (@WebMvcTest + @MockBean + MockMvc + jsonPath). Each of the 8 routes gets its own happy-path
 * test verifying it's wired to the correct TaskTraversalService method and shapes the response
 * correctly. 404 (missing task) and 400 (invalid UUID) are GlobalExceptionHandler's behavior,
 * identical regardless of which route triggers it - covered once each here on a representative
 * route, the same way Riki-Kaplan's own precedent test covers its traversal endpoints'
 * shared error handling on a single representative route rather than repeating it per endpoint.
 */
@WebMvcTest(ChainController.class)
class ChainControllerTest {

    private static final UUID TASK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TASK_ID = UUID.fromString("11111111-1111-1111-1111-111111111112");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskTraversalService taskTraversalService;

    @Test
    void getDirectDependencies_returnsOkWithTheServiceResult() throws Exception {
        when(taskTraversalService.getDirectDependencies(TASK_ID)).thenReturn(List.of(sampleResponse(OTHER_TASK_ID)));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/dependencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(OTHER_TASK_ID.toString()));
    }

    @Test
    void getAllDependencies_returnsOkWithTheServiceResult() throws Exception {
        when(taskTraversalService.getAllDependencies(TASK_ID)).thenReturn(List.of(sampleResponse(OTHER_TASK_ID)));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/dependencies/transitive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(OTHER_TASK_ID.toString()));
    }

    @Test
    void getDirectDependents_returnsOkWithTheServiceResult() throws Exception {
        when(taskTraversalService.getDirectDependents(TASK_ID)).thenReturn(List.of(sampleResponse(OTHER_TASK_ID)));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/dependents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(OTHER_TASK_ID.toString()));
    }

    @Test
    void getAllDependents_returnsOkWithTheServiceResult() throws Exception {
        when(taskTraversalService.getAllDependents(TASK_ID)).thenReturn(List.of(sampleResponse(OTHER_TASK_ID)));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/dependents/transitive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(OTHER_TASK_ID.toString()));
    }

    @Test
    void getAncestors_returnsOkWithTheServiceResult() throws Exception {
        when(taskTraversalService.getAncestors(TASK_ID)).thenReturn(List.of(sampleResponse(OTHER_TASK_ID)));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/ancestors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(OTHER_TASK_ID.toString()));
    }

    @Test
    void getDirectChildren_returnsOkWithTheServiceResult() throws Exception {
        when(taskTraversalService.getDirectChildren(TASK_ID)).thenReturn(List.of(sampleResponse(OTHER_TASK_ID)));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/children"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(OTHER_TASK_ID.toString()));
    }

    @Test
    void getAllDescendants_returnsOkWithTheServiceResult() throws Exception {
        when(taskTraversalService.getAllDescendants(TASK_ID)).thenReturn(List.of(sampleResponse(OTHER_TASK_ID)));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/descendants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(OTHER_TASK_ID.toString()));
    }

    @Test
    void getTrace_returnsOkWithBothAxesKeptSeparate() throws Exception {
        TaskTraceResponse.DependencyGraphSection dependencyGraph = new TaskTraceResponse.DependencyGraphSection(
                List.of(sampleResponse(OTHER_TASK_ID)), List.of(sampleResponse(OTHER_TASK_ID)), List.of(), List.of());
        TaskTraceResponse.HierarchySection hierarchy = new TaskTraceResponse.HierarchySection(
                List.of(), List.of(), List.of());
        when(taskTraversalService.getTrace(TASK_ID))
                .thenReturn(new TaskTraceResponse(TASK_ID, dependencyGraph, hierarchy));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(TASK_ID.toString()))
                .andExpect(jsonPath("$.dependencyGraph.directDependencies.length()").value(1))
                .andExpect(jsonPath("$.dependencyGraph.directDependencies[0].id").value(OTHER_TASK_ID.toString()))
                .andExpect(jsonPath("$.hierarchy.ancestors.length()").value(0));
    }

    @Test
    void traversal_whenTaskMissing_returnsNotFound() throws Exception {
        when(taskTraversalService.getAncestors(any())).thenThrow(new TaskNotFoundException(TASK_ID));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/ancestors"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/tasks/" + TASK_ID + "/ancestors"));
    }

    @Test
    void traversal_whenTaskIdIsNotAValidUuid_returnsBadRequest() throws Exception {
        // Message comes from GlobalExceptionHandler.handleTypeMismatch, shared across all
        // controllers - it names the actual offending parameter, not a hardcoded "task ID".
        mockMvc.perform(get("/tasks/not-a-valid-uuid/ancestors"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'id'"));
    }

    private static TaskResponse sampleResponse(UUID id) {
        return new TaskResponse(id, null, TaskStatus.PENDING, false, 0, 0, null, null);
    }
}
