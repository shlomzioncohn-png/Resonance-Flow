package com.rnd.bff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rnd.bff.client.ChainClient;
import com.rnd.bff.client.InboxClient;
import com.rnd.bff.client.InboxSelectionResponse;
import com.rnd.bff.client.MindClient;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.dto.TaskDependenciesDTO;
import com.rnd.bff.dto.TaskListResponseDTO;
import com.rnd.bff.dto.SelectedTaskDTO;
import com.rnd.bff.dto.TaskRecommendationDTO;
import com.rnd.bff.dto.TaskSelectionDTO;
import com.rnd.bff.dto.WorkspaceRequestDTO;
import com.rnd.bff.exception.InboxRejectedException;
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

    private WorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(inboxClient, chainClient, mindClient);
        // Most tests exercise the BFF's own ranking (mock mode); the
        // "Inbox selection" tests below switch this off.
        lenient().when(inboxClient.isMockEnabled()).thenReturn(true);
    }

    private static TaskDTO task(String id, String priority, Integer effortEstimate) {
        return TaskDTO.builder()
                .id(id)
                .title("Task " + id)
                .status("APPROVED")
                .isAvailable(true)
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

    @Test
    void recommend_skipsClosedSnoozedAndBlockedTasks() {
        TaskDTO done = task("done", "HIGH", 30);
        done.setStatus("DONE");
        TaskDTO snoozed = task("snoozed", "HIGH", 30);
        snoozed.setStatus("SNOOZED");
        TaskDTO blocked = task("blocked", "HIGH", 30);
        blocked.setIsAvailable(false);
        TaskDTO workable = task("workable", "LOW", 30);
        when(inboxClient.getTasksForUser(anyString())).thenReturn(List.of(done, snoozed, blocked, workable));
        when(chainClient.getFeasibility(anyString(), anyList())).thenReturn(Map.of());
        when(mindClient.getScores(anyString(), anyList(), any())).thenReturn(Map.of("workable", 85));

        TaskRecommendationDTO result =
                workspaceService.recommend(new WorkspaceRequestDTO(60, null, null), "emp_001");

        assertThat(result.getTask().getId()).isEqualTo("workable");
        assertThat(result.getAlternatives()).isEmpty();
    }

    @Test
    void recommend_equalScores_prefersTaskThatFitsThenHigherPriorityScore() {
        TaskDTO tooLong = task("too_long", "HIGH", 120);
        tooLong.setPriorityScore(9.0);
        TaskDTO fitsLow = task("fits_low", "LOW", 30);
        fitsLow.setPriorityScore(2.0);
        TaskDTO fitsHigh = task("fits_high", "HIGH", 30);
        fitsHigh.setPriorityScore(8.0);
        when(inboxClient.getTasksForUser(anyString())).thenReturn(List.of(tooLong, fitsLow, fitsHigh));
        when(chainClient.getFeasibility(anyString(), anyList())).thenReturn(Map.of());
        // Same score for every task - like Mind's mock
        when(mindClient.getScores(anyString(), anyList(), any()))
                .thenReturn(Map.of("too_long", 85, "fits_low", 85, "fits_high", 85));

        TaskRecommendationDTO result =
                workspaceService.recommend(new WorkspaceRequestDTO(60, null, null), "emp_001");

        assertThat(result.getTask().getId()).isEqualTo("fits_high");
        assertThat(result.getFitsInTime()).isTrue();
    }

    // -------------------------------------------------------------------------
    // recommend - real Inbox: Inbox's task selection
    // -------------------------------------------------------------------------

    private static InboxSelectionResponse.SelectedTask selected(String id, int effort, double score) {
        return new InboxSelectionResponse.SelectedTask(id, "Task " + id, null, effort, null, score);
    }

    @Test
    void recommend_realInbox_usesInboxSelectionFirstTaskAndAlternatives() {
        when(inboxClient.isMockEnabled()).thenReturn(false);
        when(inboxClient.selectTasks("emp_001", 60)).thenReturn(new InboxSelectionResponse(
                List.of(selected("t1", 30, 0.82), selected("t2", 20, 0.4)), 50, 1.22, 10, false, null));
        TaskDTO full = task("t1", "HIGH", 30);
        when(inboxClient.getTaskById("emp_001", "t1")).thenReturn(full);

        TaskRecommendationDTO result =
                workspaceService.recommend(new WorkspaceRequestDTO(60, null, null), "emp_001");

        assertThat(result.getTask()).isSameAs(full);
        assertThat(result.getAlternatives()).extracting(TaskDTO::getId).containsExactly("t2");
        assertThat(result.getFitsInTime()).isTrue();
        assertThat(result.getIsHighPriority()).isTrue();
        assertThat(result.getConfidenceScore()).isEqualTo(82);
        // Inbox picked it - the BFF's own ranking (Chain/Mind) isn't used
        verifyNoInteractions(chainClient, mindClient);
    }

    @Test
    void recommend_realInbox_windowWidened_reportsDoesNotFitWithInboxReason() {
        when(inboxClient.isMockEnabled()).thenReturn(false);
        String reason = "No task fit the original 15 minutes; window was automatically expanded to 21 minutes";
        when(inboxClient.selectTasks("emp_001", 15)).thenReturn(new InboxSelectionResponse(
                List.of(selected("t1", 21, 0.28)), 21, 0.28, 0, true, reason));

        TaskRecommendationDTO result =
                workspaceService.recommend(new WorkspaceRequestDTO(15, null, null), "emp_001");

        assertThat(result.getFitsInTime()).isFalse();
        assertThat(result.getExplanation()).isEqualTo(reason);
        // Full task not found -> the selected task's own fields are used
        assertThat(result.getTask().getEffortEstimate()).isEqualTo(21);
    }

    @Test
    void recommend_realInbox_selectionFails_fallsBackToBffRanking() {
        when(inboxClient.isMockEnabled()).thenReturn(false);
        when(inboxClient.selectTasks(anyString(), anyInt())).thenThrow(new RuntimeException("Inbox 500"));
        when(inboxClient.getTasksForUser(anyString())).thenReturn(List.of(task("task_001", "HIGH", 30)));
        when(chainClient.getFeasibility(anyString(), anyList())).thenReturn(Map.of());
        when(mindClient.getScores(anyString(), anyList(), any())).thenReturn(Map.of("task_001", 85));

        TaskRecommendationDTO result =
                workspaceService.recommend(new WorkspaceRequestDTO(60, null, null), "emp_001");

        assertThat(result.getTask().getId()).isEqualTo("task_001");
    }

    @Test
    void recommend_realInbox_selectionRejected_isPassedOnNotHidden() {
        when(inboxClient.isMockEnabled()).thenReturn(false);
        when(inboxClient.selectTasks(anyString(), anyInt()))
                .thenThrow(new InboxRejectedException(401, "Actor does not exist"));

        assertThatThrownBy(() -> workspaceService.recommend(new WorkspaceRequestDTO(60, null, null), "emp_001"))
                .isInstanceOf(InboxRejectedException.class);
    }

    // -------------------------------------------------------------------------
    // listTasks — "My Tasks" screen. Filtering, search and paging happen in
    // Inbox (or InboxClient's mock), so these only check what the service
    // passes down - see InboxClientTest for the mock's own filtering.
    // -------------------------------------------------------------------------

    private static TaskListResponseDTO emptyPage() {
        return TaskListResponseDTO.builder().items(List.of()).total(0).page(0).size(20).build();
    }

    @Test
    void listTasks_noFilter_passesNoStatusToInbox() {
        when(inboxClient.listTasks("emp_001", null, null, 0, 20)).thenReturn(emptyPage());

        workspaceService.listTasks("emp_001", null, null, 0, 20);

        verify(inboxClient).listTasks("emp_001", null, null, 0, 20);
    }

    @Test
    void listTasks_statusFilter_isPassedToInboxInUppercase() {
        when(inboxClient.listTasks(anyString(), any(), any(), anyInt(), anyInt())).thenReturn(emptyPage());

        workspaceService.listTasks("emp_001", "in_progress", "docs", 0, 20);

        verify(inboxClient).listTasks("emp_001", "IN_PROGRESS", "docs", 0, 20);
    }

    @Test
    void listTasks_allOrUnrecognizedFilter_failsOpenWithNoStatus() {
        when(inboxClient.listTasks(anyString(), any(), any(), anyInt(), anyInt())).thenReturn(emptyPage());

        workspaceService.listTasks("emp_001", "all", null, 0, 20);
        workspaceService.listTasks("emp_001", "not_a_real_filter", null, 0, 20);

        verify(inboxClient, times(2)).listTasks("emp_001", null, null, 0, 20);
    }

    @Test
    void listTasks_negativePageAndOversizedSize_areClampedBeforeCallingInbox() {
        when(inboxClient.listTasks(anyString(), any(), any(), anyInt(), anyInt())).thenReturn(emptyPage());

        workspaceService.listTasks("emp_001", null, null, -1, 999);

        verify(inboxClient).listTasks("emp_001", null, null, 0, 100);
    }

    @Test
    void getTask_inboxRejects_passesRejectionOnInsteadOfServiceUnavailable() {
        when(inboxClient.getTaskById(anyString(), anyString()))
                .thenThrow(new InboxRejectedException(403, "Actor cannot act on task"));

        assertThatThrownBy(() -> workspaceService.getTask("emp_001", "task_001"))
                .isInstanceOf(InboxRejectedException.class);
    }

    @Test
    void listTasks_inboxThrows_throwsServiceUnavailableForInbox() {
        when(inboxClient.listTasks(anyString(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("connection refused"));

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
        when(inboxClient.updateStatus(anyString(), anyString(), anyString(), any())).thenReturn(null);

        assertThatThrownBy(() -> workspaceService.updateTaskStatus("emp_001", "task_missing", "IN_PROGRESS", null))
                .isInstanceOf(TaskNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // selectTasks - "tasks for N minutes"
    // -------------------------------------------------------------------------

    @Test
    void selectTasks_minutesOutOfRangeOrMissing_throwsIllegalArgument() {
        for (Integer minutes : new Integer[] {null, 0, -5, 481}) {
            assertThatThrownBy(() -> workspaceService.selectTasks("emp_001", minutes))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("between 1 and 480");
        }
        verifyNoInteractions(inboxClient);
    }

    @Test
    void selectTasks_mapsInboxSelection() {
        when(inboxClient.selectTasks("emp_001", 60)).thenReturn(new InboxSelectionResponse(
                List.of(selected("t1", 30, 0.82), selected("t2", 20, 0.4)), 50, 1.22, 10, false, null));

        TaskSelectionDTO result = workspaceService.selectTasks("emp_001", 60);

        assertThat(result.getAvailableMinutes()).isEqualTo(60);
        assertThat(result.getSelectedTasks()).extracting(SelectedTaskDTO::getId).containsExactly("t1", "t2");
        assertThat(result.getSelectedTasks().get(0).getEffortEstimate()).isEqualTo(30);
        assertThat(result.getSelectedTasks().get(0).getSelectionScore()).isEqualTo(0.82);
        assertThat(result.getTotalEffortMinutes()).isEqualTo(50);
        assertThat(result.getRemainingMinutes()).isEqualTo(10);
        assertThat(result.getUsedFallback()).isFalse();
    }

    @Test
    void selectTasks_inboxDown_throwsServiceUnavailable_noFallback() {
        when(inboxClient.selectTasks(anyString(), anyInt())).thenThrow(new RuntimeException("Inbox 500"));

        assertThatThrownBy(() -> workspaceService.selectTasks("emp_001", 60))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void selectTasks_inboxRejects_isPassedOn() {
        when(inboxClient.selectTasks(anyString(), anyInt()))
                .thenThrow(new InboxRejectedException(401, "Actor does not exist"));

        assertThatThrownBy(() -> workspaceService.selectTasks("emp_001", 60))
                .isInstanceOf(InboxRejectedException.class);
    }
}
