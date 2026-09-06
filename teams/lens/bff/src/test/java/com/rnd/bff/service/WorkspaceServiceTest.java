package com.rnd.bff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.rnd.bff.client.ChainClient;
import com.rnd.bff.client.InboxClient;
import com.rnd.bff.client.MindClient;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.dto.TaskDependenciesDTO;
import com.rnd.bff.dto.TaskRecommendationDTO;
import com.rnd.bff.dto.WorkspaceRequestDTO;
import com.rnd.bff.exception.ServiceUnavailableException;
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

    private WorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(inboxClient, chainClient, mindClient);
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
}
