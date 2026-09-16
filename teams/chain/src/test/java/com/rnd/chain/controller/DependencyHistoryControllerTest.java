package com.rnd.chain.controller;

import com.rnd.chain.dto.DependencyChangeAction;
import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;
import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.service.TaskDependencyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web-layer slice test: TaskDependencyService is mocked, so this only verifies routing, param validation and JSON shape. */
@ExtendWith(SpringExtension.class)
@WebMvcTest(DependencyHistoryController.class)
class DependencyHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskDependencyService taskDependencyService;

    private final UUID taskA = UUID.randomUUID();

    @Test
    void getHistory_returnsEmptyPage_whenTaskExistsWithNoChanges() throws Exception {
        when(taskDependencyService.getHistory(taskA, 0, 20))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/tasks/{taskId}/dependencies/history", taskA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void getHistory_returns404_whenTaskDoesNotExist() throws Exception {
        UUID unknownTaskId = UUID.randomUUID();
        when(taskDependencyService.getHistory(eq(unknownTaskId), any(Integer.class), any(Integer.class)))
                .thenThrow(new TaskNotFoundException("Task not found: " + unknownTaskId));

        mockMvc.perform(get("/tasks/{taskId}/dependencies/history", unknownTaskId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void getHistory_returnsEntriesNewestFirst_afterAddAndRemove() throws Exception {
        UUID taskB = UUID.randomUUID();
        DependencyHistoryEntry added = newEntry(taskA, taskB, DependencyChangeAction.ADDED, Instant.parse("2026-01-01T12:00:00Z"));
        DependencyHistoryEntry removed = newEntry(taskA, taskB, DependencyChangeAction.REMOVED, Instant.parse("2026-01-01T12:00:01Z"));
        when(taskDependencyService.getHistory(taskA, 0, 20))
                .thenReturn(new PageResponse<>(List.of(removed, added), 0, 20, 2, 1));

        mockMvc.perform(get("/tasks/{taskId}/dependencies/history", taskA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].action").value("REMOVED"))
                .andExpect(jsonPath("$.content[1].action").value("ADDED"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void getHistory_paginates() throws Exception {
        UUID taskB = UUID.randomUUID();
        DependencyHistoryEntry entry = newEntry(taskA, taskB, DependencyChangeAction.ADDED, Instant.now());
        when(taskDependencyService.getHistory(taskA, 0, 1))
                .thenReturn(new PageResponse<>(List.of(entry), 0, 1, 3, 3));

        mockMvc.perform(get("/tasks/{taskId}/dependencies/history", taskA)
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void getHistory_rejectsSizeZero() throws Exception {
        mockMvc.perform(get("/tasks/{taskId}/dependencies/history", taskA).param("size", "0"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(taskDependencyService);
    }

    @Test
    void getHistory_rejectsSizeOver100() throws Exception {
        mockMvc.perform(get("/tasks/{taskId}/dependencies/history", taskA).param("size", "101"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(taskDependencyService);
    }

    @Test
    void getHistory_rejectsNegativePage() throws Exception {
        mockMvc.perform(get("/tasks/{taskId}/dependencies/history", taskA).param("page", "-1"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(taskDependencyService);
    }

    private static DependencyHistoryEntry newEntry(UUID taskId, UUID dependsOnTaskId,
                                                     DependencyChangeAction action, Instant createdAt) {
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
