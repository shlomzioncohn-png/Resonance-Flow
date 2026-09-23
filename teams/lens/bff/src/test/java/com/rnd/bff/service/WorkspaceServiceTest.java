package com.rnd.bff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.rnd.bff.client.ChainClient;
import com.rnd.bff.client.EmployeeClient;
import com.rnd.bff.client.InboxClient;
import com.rnd.bff.client.MindClient;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.dto.TaskDependenciesDTO;
import com.rnd.bff.dto.TaskListResponseDTO;
import com.rnd.bff.dto.TaskRecommendationDTO;
import com.rnd.bff.dto.WorkspaceRequestDTO;
import com.rnd.bff.exception.ServiceUnavailableException;
import com.rnd.bff.exception.TaskNotFoundException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the branches called out in PR #236 review comment
 * https://github.com/KamaTechOrg/Resonance-Flow/pull/236#issuecomment-5505743869:
 * null-safe handling of a partial Chain map, a missing effort estimate, and
 * per-dependency degraded/failed behavior when Inbox, Chain, or Mind is down.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    @Mock private InboxClient inboxClient;
    @Mock private ChainClient chainClient;
    @Mock private MindClient mindClient;
    @Mock private EmployeeClient employeeClient;

    private WorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(inboxClient, chainClient, mindClient, employeeClient);
    }

    private static TaskDTO task(String id, String priority, Integer effortEstimate) {
        return TaskDTO.builder()
                .id(id)
                .title("Task " + id)
                .status("READY")
                .priority(priority)
                .effortEstimate(effortEstimate)
                .project("Platform Service")
                .build();
    }

    @Test
    void recommend_chainHasNoEntryForTask_doesNotThrowAndTreatsDependenciesAsUnknown() {
        TaskDTO task = task("task_001", "HIGH", 30);
        when(inboxClient.getTasksForUser(anyString())).thenReturn(List.of(task));
        // Chain returns a map with no entry for task_001 - a partial response,
        // not a missing/erroring call.
        when(chainClient.getFeasibility(anyString(), anyList())).thenReturn(Map.of());
        when(mindClient.getScores(anyString(), anyList(), any())).thenReturn(Map.of("task_001", 85));

        TaskRecommendationDTO result =
                workspaceService.recommend(new WorkspaceRequestDTO(60, null, null), "emp_001");

        assertThat(result.getTask().getId()).isEqualTo("task_001");
        assertThat(result.getHasNoDependencies()).isFalse();
    }

    @Test
    void recommend_taskWithNullEffortEstimate_reportsDoesNotFitInsteadOfThrowing() {
        TaskDTO task = task("task_001", "HIGH", null);
        when(inboxClient.getTasksForUser(anyString())).thenReturn(List.of(task));
        when(chainClient.getFeasibility(anyString(), anyList()))
                .thenReturn(Map.of("task_001", new TaskDependenciesDTO(List.of(), List.of())));
        when(mindClient.getScores(anyString(), anyList(), any())).thenReturn(Map.of("task_001", 85));

        TaskRecommendationDTO result =
                workspaceService.recommend(new WorkspaceRequestDTO(60, null, null), "emp_001");

        assertThat(result.getFitsInTime()).isFalse();
    }

    @Test
    void recommend_inboxThrows_throwsServiceUnavailableForInbox() {
        when(inboxClient.getTasksForUser(anyString())).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(
                        () -> workspaceService.recommend(new WorkspaceRequestDTO(60, null, null), "emp_001"))
                .isInstanceOf(ServiceUnavailableException.class)
                .satisfies(
                        ex -> assertThat(((ServiceUnavailableException) ex).getServiceName()).isEqualTo("Inbox"));
    }

    @Test
    void recommend_chainThrows_degradesInsteadOfFailing() {
        TaskDTO task = task("task_001", "HIGH", 30);
        when(inboxClient.getTasksForUser(anyString())).thenReturn(List.of(task));
        when(chainClient.getFeasibility(anyString(), anyList()))
                .thenThrow(new RuntimeException("connection refused"));
        when(mindClient.getScores(anyString(), anyList(), any())).thenReturn(Map.of("task_001", 85));

        TaskRecommendationDTO result =
                workspaceService.recommend(new WorkspaceRequestDTO(60, null, null), "emp_001");

        assertThat(result.getTask().getId()).isEqualTo("task_001");
        assertThat(result.getHasNoDependencies()).isFalse();
        assertThat(result.getExplanation()).contains("dependency data unavailable");
    }

    @Test
    void recommend_mindThrows_degradesToDefaultOrderWithZeroScore() {
        TaskDTO task = task("task_001", "HIGH", 30);
        when(inboxClient.getTasksForUser(anyString())).thenReturn(List.of(task));
        when(chainClient.getFeasibility(anyString(), anyList()))
                .thenReturn(Map.of("task_001", new TaskDependenciesDTO(List.of(), List.of())));
        when(mindClient.getScores(anyString(), anyList(), any()))
                .thenThrow(new RuntimeException("connection refused"));

        TaskRecommendationDTO result =
                workspaceService.recommend(new WorkspaceRequestDTO(60, null, null), "emp_001");

        assertThat(result.getTask().getId()).isEqualTo("task_001");
        assertThat(result.getConfidenceScore()).isZero();
        assertThat(result.getExplanation()).contains("ranking unavailable");
    }

    // -------------------------------------------------------------------------
    // listTasks — "My Tasks" screen
    // -------------------------------------------------------------------------

    private static List<TaskDTO> fourTasksAcrossStatuses() {
        return List.of(
                taskWithStatus("task_001", "READY", "Fix login bug"),
                taskWithStatus("task_002", "IN_PROGRESS", "Update documentation"),
                taskWithStatus("task_003", "BLOCKED", "Update customer proposal"),
                taskWithStatus("task_004", "READY", "Write onboarding guide"));
    }

    // Distinct name from task(id, priority, effortEstimate) above - same arg
    // count with a String third parameter would still resolve unambiguously
    // by type, but two same-named overloads with unrelated meanings would be
    // needlessly confusing to a reader.
    private static TaskDTO taskWithStatus(String id, String status, String title) {
        return TaskDTO.builder().id(id).title(title).status(status).priority("MEDIUM").build();
    }

    @Test
    void listTasks_noFilter_returnsEverythingWithCorrectTotal() {
        when(inboxClient.getTasksForUser(anyString())).thenReturn(fourTasksAcrossStatuses());

        TaskListResponseDTO result = workspaceService.listTasks("emp_001", null, null, 0, 20);

        assertThat(result.getTotal()).isEqualTo(4);
        assertThat(result.getItems()).hasSize(4);
    }

    @Test
    void listTasks_filterInProgress_returnsOnlyInProgressTasks() {
        when(inboxClient.getTasksForUser(anyString())).thenReturn(fourTasksAcrossStatuses());

        TaskListResponseDTO result = workspaceService.listTasks("emp_001", "in_progress", null, 0, 20);

        assertThat(result.getItems()).extracting(TaskDTO::getId).containsExactly("task_002");
    }

    @Test
    void listTasks_filterWaitingOnMe_mapsToReadyStatus() {
        // Judgment call documented in WorkspaceService.listTasks: waiting_on_me == READY.
        when(inboxClient.getTasksForUser(anyString())).thenReturn(fourTasksAcrossStatuses());

        TaskListResponseDTO result = workspaceService.listTasks("emp_001", "waiting_on_me", null, 0, 20);

        assertThat(result.getItems()).extracting(TaskDTO::getId).containsExactly("task_001", "task_004");
    }

    @Test
    void listTasks_unrecognizedFilterValue_failsOpenAndReturnsEverything() {
        when(inboxClient.getTasksForUser(anyString())).thenReturn(fourTasksAcrossStatuses());

        TaskListResponseDTO result = workspaceService.listTasks("emp_001", "not_a_real_filter", null, 0, 20);

        assertThat(result.getTotal()).isEqualTo(4);
    }

    @Test
    void listTasks_search_isCaseInsensitiveAndMatchesTitleOnly() {
        when(inboxClient.getTasksForUser(anyString())).thenReturn(fourTasksAcrossStatuses());

        TaskListResponseDTO result = workspaceService.listTasks("emp_001", null, "DOCUMENTATION", 0, 20);

        assertThat(result.getItems()).extracting(TaskDTO::getId).containsExactly("task_002");
    }

    @Test
    void listTasks_pageBeyondLastPage_returnsEmptyItemsNotAnError() {
        when(inboxClient.getTasksForUser(anyString())).thenReturn(fourTasksAcrossStatuses());

        TaskListResponseDTO result = workspaceService.listTasks("emp_001", null, null, 5, 20);

        assertThat(result.getItems()).isEmpty();
        assertThat(result.getTotal()).isEqualTo(4);
    }

    @Test
    void listTasks_negativePageAndOversizedSize_areClampedInsteadOfThrowing() {
        when(inboxClient.getTasksForUser(anyString())).thenReturn(fourTasksAcrossStatuses());

        TaskListResponseDTO result = workspaceService.listTasks("emp_001", null, null, -1, 999);

        assertThat(result.getPage()).isZero();
        assertThat(result.getSize()).isEqualTo(100);
        assertThat(result.getItems()).hasSize(4);
    }

    @Test
    void listTasks_inboxThrows_throwsServiceUnavailableForInbox() {
        when(inboxClient.getTasksForUser(anyString())).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> workspaceService.listTasks("emp_001", null, null, 0, 20))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    // -------------------------------------------------------------------------
    // getTask / updateTaskStatus
    // -------------------------------------------------------------------------

    @Test
    void getTask_unknownId_throwsTaskNotFoundException() {
        when(inboxClient.getTaskById(anyString(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> workspaceService.getTask("emp_001", "task_missing"))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void updateTaskStatus_unknownId_throwsTaskNotFoundException() {
        when(inboxClient.updateStatus(anyString(), anyString(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> workspaceService.updateTaskStatus("emp_001", "task_missing", "BLOCKED"))
                .isInstanceOf(TaskNotFoundException.class);
    }
}
