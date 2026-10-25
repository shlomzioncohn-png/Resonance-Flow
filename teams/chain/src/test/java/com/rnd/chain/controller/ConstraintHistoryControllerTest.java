package com.rnd.chain.controller;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.ConstraintHistoryAction;
import com.rnd.chain.dto.ConstraintHistoryEntry;
import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.exception.GlobalExceptionHandler;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.service.ConstraintHistoryService;
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

@WebMvcTest(ConstraintHistoryController.class)
@ContextConfiguration(classes = {ConstraintHistoryController.class, GlobalExceptionHandler.class})
class ConstraintHistoryControllerTest {

    private static final UUID TASK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");
    private static final String URL = "/tasks/" + TASK_ID + "/constraints/history";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConstraintHistoryService service;

    @MockBean
    private TenantContext tenantContext;

    @MockBean
    private TaskOwnershipService taskOwnershipService;

    private PageResponse<ConstraintHistoryEntry> pageOf(ConstraintHistoryEntry... entries) {
        return new PageResponse<>(List.of(entries), 0, 20, entries.length, entries.length == 0 ? 0 : 1);
    }

    @Test
    void getConstraintHistory_returnsPageWithDefaultsAndOpenRange() throws Exception {
        ConstraintHistoryEntry created = new ConstraintHistoryEntry(UUID.randomUUID(), "c-1", TASK_ID,
                ConstraintHistoryAction.CREATED, null, Constraint.ConstraintStatus.PENDING, "ref", T1);
        when(service.getHistory(TASK_ID, null, null, 0, 20)).thenReturn(pageOf(created));

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].constraintId").value("c-1"))
                .andExpect(jsonPath("$.content[0].action").value("CREATED"))
                .andExpect(jsonPath("$.content[0].newStatus").value("PENDING"))
                .andExpect(jsonPath("$.content[0].previousStatus").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getConstraintHistory_passesRangeAndPagingToTheService() throws Exception {
        Instant to = Instant.parse("2026-10-05T00:00:00Z");
        when(service.getHistory(TASK_ID, T1, to, 2, 5)).thenReturn(pageOf());

        mockMvc.perform(get(URL)
                        .param("from", "2026-10-01T10:00:00Z")
                        .param("to", "2026-10-05T00:00:00Z")
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        verify(service).getHistory(TASK_ID, T1, to, 2, 5);
    }

    @Test
    void getConstraintHistory_unknownTask_returns404() throws Exception {
        when(service.getHistory(eq(TASK_ID), any(), any(), anyInt(), anyInt()))
                .thenThrow(new TaskNotFoundException("Task not found: " + TASK_ID));

        mockMvc.perform(get(URL)).andExpect(status().isNotFound());
    }

    @Test
    void getConstraintHistory_invalidRequest_returns400() throws Exception {
        when(service.getHistory(eq(TASK_ID), any(), any(), anyInt(), anyInt()))
                .thenThrow(new InvalidRequestException("from must not be after to"));

        mockMvc.perform(get(URL)).andExpect(status().isBadRequest());
    }

    @Test
    void getConstraintHistory_malformedDate_returns400() throws Exception {
        mockMvc.perform(get(URL).param("from", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("TYPE_MISMATCH"));
    }

    @Test
    void getConstraintHistory_dateWithoutOffset_returns400() throws Exception {
        mockMvc.perform(get(URL).param("to", "2026-10-01T10:00:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getConstraintHistory_malformedTaskId_returns400() throws Exception {
        mockMvc.perform(get("/tasks/not-a-uuid/constraints/history"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getConstraintHistory_nonNumericPage_returns400() throws Exception {
        mockMvc.perform(get(URL).param("page", "abc"))
                .andExpect(status().isBadRequest());
    }
}
