package com.rnd.chain.controller;

import com.rnd.chain.dto.DependencyChangeAction;
import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;
import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.dto.TaskResponse;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTraceResponse;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.service.DependencyGraphService;
import com.rnd.chain.service.TaskDependencyService;
import com.rnd.chain.service.TaskTraversalService;
import com.rnd.chain.exception.GlobalExceptionHandler;
import org.springframework.context.annotation.Import;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChainController.class)
@Import(GlobalExceptionHandler.class)
class ChainControllerTest {

    private static final UUID TASK_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID OTHER_TASK_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111112");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskTraversalService taskTraversalService;

    @MockBean
    private TaskDependencyService taskDependencyService;

    @MockBean
    private DependencyGraphService dependencyGraphService;

    @Test
    void getDirectDependencies_returnsOkWithTheServiceResult() throws Exception {
        when(taskTraversalService.getDirectDependencies(TASK_ID))
                .thenReturn(List.of(sampleResponse(OTHER_TASK_ID)));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/dependencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(OTHER_TASK_ID.toString()));
    }

    @Test
    void getAllDependencies_returnsOkWithTheServiceResult() throws Exception {
        when(taskTraversalService.getAllDependencies(TASK_ID))
                .thenReturn(List.of(sampleResponse(OTHER_TASK_ID)));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/dependencies/transitive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(OTHER_TASK_ID.toString()));
    }

    @Test
    void getDirectDependents_returnsOkWithTheServiceResult() throws Exception {
        when(taskTraversalService.getDirectDependents(TASK_ID))
                .thenReturn(List.of(sampleResponse(OTHER_TASK_ID)));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/dependents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(OTHER_TASK_ID.toString()));
    }

    @Test
    void getAllDependents_returnsOkWithTheServiceResult() throws Exception {
        when(taskTraversalService.getAllDependents(TASK_ID))
                .thenReturn(List.of(sampleResponse(OTHER_TASK_ID)));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/dependents/transitive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(OTHER_TASK_ID.toString()));
    }

    @Test
    void getAncestors_returnsOkWithTheServiceResult() throws Exception {
        when(taskTraversalService.getAncestors(TASK_ID))
                .thenReturn(List.of(sampleResponse(OTHER_TASK_ID)));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/ancestors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(OTHER_TASK_ID.toString()));
    }

    @Test
    void getDirectChildren_returnsOkWithTheServiceResult() throws Exception {
        when(taskTraversalService.getDirectChildren(TASK_ID))
                .thenReturn(List.of(sampleResponse(OTHER_TASK_ID)));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/children"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(OTHER_TASK_ID.toString()));
    }

    @Test
    void getAllDescendants_returnsOkWithTheServiceResult() throws Exception {
        when(taskTraversalService.getAllDescendants(TASK_ID))
                .thenReturn(List.of(sampleResponse(OTHER_TASK_ID)));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/descendants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(OTHER_TASK_ID.toString()));
    }

    @Test
    void getTrace_returnsOkWithBothAxesKeptSeparate() throws Exception {
        TaskTraceResponse.DependencyGraphSection dependencyGraph =
                new TaskTraceResponse.DependencyGraphSection(
                        List.of(sampleResponse(OTHER_TASK_ID)),
                        List.of(sampleResponse(OTHER_TASK_ID)),
                        List.of(),
                        List.of());

        TaskTraceResponse.HierarchySection hierarchy =
                new TaskTraceResponse.HierarchySection(
                        List.of(),
                        List.of(),
                        List.of());

        when(taskTraversalService.getTrace(TASK_ID))
                .thenReturn(new TaskTraceResponse(
                        TASK_ID,
                        dependencyGraph,
                        hierarchy));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(TASK_ID.toString()))
                .andExpect(jsonPath("$.dependencyGraph.directDependencies.length()").value(1))
                .andExpect(jsonPath("$.dependencyGraph.directDependencies[0].id")
                        .value(OTHER_TASK_ID.toString()))
                .andExpect(jsonPath("$.hierarchy.ancestors.length()").value(0));
    }

    @Test
    void getBlockers_returnsOkAndDelegatesToDependencyGraphService() throws Exception {
        when(dependencyGraphService.getBlockers(TASK_ID))
                .thenReturn(null);

        mockMvc.perform(get("/tasks/" + TASK_ID + "/blockers"))
                .andExpect(status().isOk());

        verify(dependencyGraphService).getBlockers(TASK_ID);
    }

    @Test
    void getHistory_returnsEmptyPage_whenTaskExistsWithNoChanges() throws Exception {
        when(taskDependencyService.getHistory(TASK_ID, 0, 20))
                .thenReturn(new PageResponse<>(
                        List.of(),
                        0,
                        20,
                        0,
                        0));

        mockMvc.perform(
                        get("/tasks/{taskId}/dependencies/history", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void getHistory_returns404_whenTaskDoesNotExist() throws Exception {
        UUID unknownTaskId = UUID.randomUUID();

        when(taskDependencyService.getHistory(
                eq(unknownTaskId),
                any(Integer.class),
                any(Integer.class)))
                .thenThrow(
                        new TaskNotFoundException(
                                "Task not found: " + unknownTaskId));

        mockMvc.perform(
                        get("/tasks/{taskId}/dependencies/history",
                                unknownTaskId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void getHistory_returnsEntriesNewestFirst_afterAddAndRemove()
            throws Exception {

        DependencyHistoryEntry added =
                newEntry(
                        TASK_ID,
                        OTHER_TASK_ID,
                        DependencyChangeAction.ADDED,
                        Instant.parse("2026-01-01T12:00:00Z"));

        DependencyHistoryEntry removed =
                newEntry(
                        TASK_ID,
                        OTHER_TASK_ID,
                        DependencyChangeAction.REMOVED,
                        Instant.parse("2026-01-01T12:00:01Z"));

        when(taskDependencyService.getHistory(TASK_ID, 0, 20))
                .thenReturn(
                        new PageResponse<>(
                                List.of(removed, added),
                                0,
                                20,
                                2,
                                1));

        mockMvc.perform(
                        get("/tasks/{taskId}/dependencies/history", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].action")
                        .value("REMOVED"))
                .andExpect(jsonPath("$.content[1].action")
                        .value("ADDED"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void getHistory_paginates() throws Exception {
        DependencyHistoryEntry entry =
                newEntry(
                        TASK_ID,
                        OTHER_TASK_ID,
                        DependencyChangeAction.ADDED,
                        Instant.now());

        when(taskDependencyService.getHistory(TASK_ID, 0, 1))
                .thenReturn(
                        new PageResponse<>(
                                List.of(entry),
                                0,
                                1,
                                3,
                                3));

        mockMvc.perform(
                        get("/tasks/{taskId}/dependencies/history", TASK_ID)
                                .param("page", "0")
                                .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void getHistory_propagatesInvalidRequestExceptionFromService_as400()
            throws Exception {

        when(taskDependencyService.getHistory(
                eq(TASK_ID),
                any(Integer.class),
                any(Integer.class)))
                .thenThrow(
                        new InvalidRequestException(
                                "page must be >= 0 and size must be in 1..100"));

        mockMvc.perform(
                        get("/tasks/{taskId}/dependencies/history", TASK_ID)
                                .param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void traversal_whenTaskMissing_returnsNotFound() throws Exception {
        when(taskTraversalService.getAncestors(any()))
                .thenThrow(new TaskNotFoundException(TASK_ID));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/ancestors"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path")
                        .value("/tasks/" + TASK_ID + "/ancestors"));
    }

    @Test
    void traversal_whenTaskIdIsNotAValidUuid_returnsBadRequest()
            throws Exception {

        mockMvc.perform(
                        get("/tasks/not-a-valid-uuid/ancestors"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message")
                        .value("Invalid value for parameter 'taskId'"));
    }

    private static TaskResponse sampleResponse(UUID id) {
        return new TaskResponse(
                id,
                null,
                TaskStatus.PENDING,
                false,
                0,
                0,
                null,
                null);
    }

    private static DependencyHistoryEntry newEntry(
            UUID taskId,
            UUID dependsOnTaskId,
            DependencyChangeAction action,
            Instant createdAt) {

        return DependencyHistoryEntry.builder()
                .id(UUID.randomUUID())
                .taskId(taskId)
                .dependsOnTaskId(dependsOnTaskId)
                .action(action)
                .source(DependencyChangeSource.USER)
                .createdAt(createdAt)
                .build();
    }
}