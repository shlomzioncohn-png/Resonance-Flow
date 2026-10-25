package com.rnd.chain.controller;

import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.dto.TaskStateHistoryEntry;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.exception.GlobalExceptionHandler;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.service.TaskStateHistoryService;
import com.rnd.chain.tenant.TaskOwnershipService;
import com.rnd.chain.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskStateHistoryController.class)
@ContextConfiguration(classes = {TaskStateHistoryController.class, GlobalExceptionHandler.class})
class TaskStateHistoryControllerTest {

    private static final UUID TASK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskStateHistoryService service;

    @MockBean
    private TenantContext tenantContext;

    @MockBean
    private TaskOwnershipService taskOwnershipService;

    private PageResponse<TaskStateHistoryEntry> pageOf(TaskStateHistoryEntry... entries) {
        return new PageResponse<>(List.of(entries), 0, 20, entries.length, entries.length == 0 ? 0 : 1);
    }

    @Test
    void getStateHistory_returnsPageWithDefaultsAndOpenRange() throws Exception {
        TaskStateHistoryEntry entry = TaskStateHistoryEntry.statusChanged(
                UUID.randomUUID(), TASK_ID, TaskStatus.PENDING, TaskStatus.DONE, T1);
        when(service.getHistory(TASK_ID, null, null, 0, 20)).thenReturn(pageOf(entry));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/state-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].eventType").value("STATUS_CHANGED"))
                .andExpect(jsonPath("$.content[0].fromStatus").value("PENDING"))
                .andExpect(jsonPath("$.content[0].blockingCountBefore").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getStateHistory_passesRangeAndPagingToTheService() throws Exception {
        Instant to = Instant.parse("2026-10-05T00:00:00Z");
        when(service.getHistory(TASK_ID, T1, to, 2, 5)).thenReturn(pageOf());

        mockMvc.perform(get("/tasks/" + TASK_ID + "/state-history")
                        .param("from", "2026-10-01T10:00:00Z")
                        .param("to", "2026-10-05T00:00:00Z")
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        verify(service).getHistory(TASK_ID, T1, to, 2, 5);
    }

    @Test
    void getStateHistory_unknownTask_returns404() throws Exception {
        when(service.getHistory(eq(TASK_ID), any(), any(), anyInt(), anyInt()))
                .thenThrow(new TaskNotFoundException("Task not found: " + TASK_ID));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/state-history"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getStateHistory_invalidRequest_returns400() throws Exception {
        when(service.getHistory(eq(TASK_ID), any(), any(), anyInt(), anyInt()))
                .thenThrow(new InvalidRequestException("from must not be after to"));

        mockMvc.perform(get("/tasks/" + TASK_ID + "/state-history"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getStateHistory_malformedDate_returns400() throws Exception {
        mockMvc.perform(get("/tasks/" + TASK_ID + "/state-history").param("from", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("TYPE_MISMATCH"));
    }

    @Test
    void getStateHistory_dateWithoutOffset_returns400() throws Exception {
        mockMvc.perform(get("/tasks/" + TASK_ID + "/state-history").param("to", "2026-10-01T10:00:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getStateHistory_malformedTaskId_returns400() throws Exception {
        mockMvc.perform(get("/tasks/not-a-uuid/state-history"))
                .andExpect(status().isBadRequest());
    }
}
