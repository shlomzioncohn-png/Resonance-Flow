package com.rnd.bff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rnd.bff.client.InboxClient;
import com.rnd.bff.dto.ManagerKpisDTO;
import com.rnd.bff.dto.ManagerProjectDTO;
import com.rnd.bff.dto.ProjectTaskCountsDTO;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.exception.InboxRejectedException;
import com.rnd.bff.exception.ServiceUnavailableException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class ManagerKpisServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private static final String PROJECT_A = "11111111-1111-1111-1111-111111111111";
    private static final String PROJECT_B = "22222222-2222-2222-2222-222222222222";
    private static final String PROJECT_C = "33333333-3333-3333-3333-333333333333";

    @Mock private InboxClient inboxClient;

    private ManagerKpisService service;

    @BeforeEach
    void setUp() {
        ZoneId zone = ZoneId.of("Asia/Jerusalem");
        Clock fixed = Clock.fixed(TODAY.atTime(12, 0).atZone(zone).toInstant(), zone);
        service = new ManagerKpisService(inboxClient, fixed);
    }

    private static TaskDTO task(String status, Boolean available, String dueDate, String projectId) {
        return TaskDTO.builder()
                .id(status + "-" + dueDate)
                .status(status)
                .inboxStatus(status)
                .isAvailable(available)
                .dueDate(dueDate)
                .projectId(projectId)
                .build();
    }

    // --- counting ---

    @Test
    void countsInProgressBlockedAndDelayed() {
        List<TaskDTO> tasks = List.of(
                task("IN_PROGRESS", true, "30/10/2026", PROJECT_A),   // in progress
                task("IN_PROGRESS", false, "01/10/2026", PROJECT_A),  // in progress + blocked + delayed
                task("APPROVED", false, null, PROJECT_A),             // blocked
                task("NEW", true, "20/10/2026", PROJECT_B),           // delayed (yesterday)
                task("SNOOZED", true, "21/10/2026", PROJECT_B));      // due today: not delayed yet

        ManagerKpisDTO kpis = ManagerKpisService.count(tasks, null, TODAY);

        assertThat(kpis.getInProgress()).isEqualTo(2);
        assertThat(kpis.getBlocked()).isEqualTo(2);
        assertThat(kpis.getDelayed()).isEqualTo(2);
        assertThat(kpis.getAvgBlockedDays()).isNull();
    }

    @Test
    void closedTasksAreNeverBlockedOrDelayed() {
        List<TaskDTO> tasks = List.of(
                task("DONE", false, "01/01/2026", PROJECT_A),
                task("REJECTED", false, "01/01/2026", PROJECT_A),
                task("CANCELLED", false, "01/01/2026", PROJECT_A));

        ManagerKpisDTO kpis = ManagerKpisService.count(tasks, null, TODAY);

        assertThat(kpis.getInProgress()).isZero();
        assertThat(kpis.getBlocked()).isZero();
        assertThat(kpis.getDelayed()).isZero();
    }

    @Test
    void unknownAvailabilityIsNotCountedAsBlocked() {
        ManagerKpisDTO kpis = ManagerKpisService.count(List.of(task("NEW", null, null, PROJECT_A)), null, TODAY);

        assertThat(kpis.getBlocked()).isZero();
    }

    @Test
    void unreadableDeadlineIsNotCountedAsDelayed() {
        ManagerKpisDTO kpis = ManagerKpisService.count(
                List.of(task("NEW", true, "2026-01-01T10:00:00Z", PROJECT_A)), null, TODAY);

        assertThat(kpis.getDelayed()).isZero();
    }

    @Test
    void fallsBackToStatusWhenInboxStatusIsMissing() {
        TaskDTO task = TaskDTO.builder().id("t1").status("in_progress").build();

        assertThat(ManagerKpisService.count(List.of(task), null, TODAY).getInProgress()).isEqualTo(1);
    }

    @Test
    void emptyTenantCountsZero() {
        ManagerKpisDTO kpis = ManagerKpisService.count(List.of(), null, TODAY);

        assertThat(kpis.getInProgress()).isZero();
        assertThat(kpis.getBlocked()).isZero();
        assertThat(kpis.getDelayed()).isZero();
        assertThat(kpis.getAvgBlockedDays()).isNull();
    }

    // --- project filter ---

    @Test
    void projectFilterCountsOnlyThatProjectsTasks() {
        List<TaskDTO> tasks = List.of(
                task("IN_PROGRESS", false, "01/10/2026", PROJECT_A),
                task("IN_PROGRESS", true, null, PROJECT_B),
                task("NEW", false, "01/10/2026", PROJECT_B),
                task("NEW", true, null, null));  // no project: only in the tenant-wide count

        ManagerKpisDTO onlyA = ManagerKpisService.count(tasks, PROJECT_A, TODAY);
        assertThat(onlyA.getInProgress()).isEqualTo(1);
        assertThat(onlyA.getBlocked()).isEqualTo(1);
        assertThat(onlyA.getDelayed()).isEqualTo(1);

        ManagerKpisDTO onlyB = ManagerKpisService.count(tasks, PROJECT_B, TODAY);
        assertThat(onlyB.getInProgress()).isEqualTo(1);
        assertThat(onlyB.getBlocked()).isEqualTo(1);
        assertThat(onlyB.getDelayed()).isEqualTo(1);

        ManagerKpisDTO all = ManagerKpisService.count(tasks, null, TODAY);
        assertThat(all.getInProgress()).isEqualTo(2);
        assertThat(all.getBlocked()).isEqualTo(2);
        assertThat(all.getDelayed()).isEqualTo(2);
    }

    @Test
    void unknownProjectCountsZero() {
        List<TaskDTO> tasks = List.of(task("IN_PROGRESS", false, "01/10/2026", PROJECT_A));

        ManagerKpisDTO kpis = ManagerKpisService.count(tasks, "no-such-project", TODAY);

        assertThat(kpis.getInProgress()).isZero();
        assertThat(kpis.getBlocked()).isZero();
        assertThat(kpis.getDelayed()).isZero();
    }

    // --- getKpis: fetching, period, errors ---

    @Test
    void getKpisCountsTheActorsTenantTasksAsOfToday() {
        when(inboxClient.getTenantTasks("mgr_001")).thenReturn(List.of(
                task("IN_PROGRESS", true, "20/10/2026", PROJECT_A),
                task("NEW", false, "21/10/2026", PROJECT_B)));

        ManagerKpisDTO kpis = service.getKpis("mgr_001", null, null);

        assertThat(kpis.getInProgress()).isEqualTo(1);
        assertThat(kpis.getBlocked()).isEqualTo(1);
        assertThat(kpis.getDelayed()).isEqualTo(1);
    }

    @Test
    void getKpisAppliesTheProjectFilter() {
        when(inboxClient.getTenantTasks("mgr_001")).thenReturn(List.of(
                task("IN_PROGRESS", true, null, PROJECT_A),
                task("IN_PROGRESS", true, null, PROJECT_B)));

        assertThat(service.getKpis("mgr_001", null, PROJECT_B).getInProgress()).isEqualTo(1);
    }

    @Test
    void validPeriodIsAcceptedAndDoesNotChangeTheCounts() {
        List<TaskDTO> tasks = List.of(task("IN_PROGRESS", false, "01/10/2026", PROJECT_A));
        when(inboxClient.getTenantTasks("mgr_001")).thenReturn(tasks);

        assertThat(service.getKpis("mgr_001", "7d", null)).isEqualTo(service.getKpis("mgr_001", null, null));
        assertThat(service.getKpis("mgr_001", "90d", null)).isEqualTo(service.getKpis("mgr_001", null, null));
    }

    @Test
    void unknownPeriodIsRejectedWithoutCallingInbox() {
        assertThatThrownBy(() -> service.getKpis("mgr_001", "1y", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("7d, 30d, 90d");
        verifyNoInteractions(inboxClient);
    }

    @Test
    void inboxRejectionPassesThrough() {
        InboxRejectedException rejected = new InboxRejectedException(403, "Actor has no valid scope for tasks");
        when(inboxClient.getTenantTasks("mgr_001")).thenThrow(rejected);

        assertThatThrownBy(() -> service.getKpis("mgr_001", null, null)).isSameAs(rejected);
    }

    @Test
    void inboxDownBecomesServiceUnavailable() {
        when(inboxClient.getTenantTasks("mgr_001")).thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> service.getKpis("mgr_001", null, null))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    // --- countByProject: the Dashboard's charts ---

    private static TaskDTO projectTask(String status, String dueDate, String projectId, String projectName) {
        TaskDTO task = task(status, true, dueDate, projectId);
        task.setProject(projectName);
        return task;
    }

    @Test
    void countsEachProjectsTasksByState() {
        List<ProjectTaskCountsDTO> projects = ManagerKpisService.countByProject(List.of(
                projectTask("IN_PROGRESS", null, PROJECT_A, "Alpha"),
                projectTask("SNOOZED", null, PROJECT_A, "Alpha"),
                projectTask("NEW", null, PROJECT_A, "Alpha"),
                projectTask("IN_REVIEW", null, PROJECT_A, "Alpha"),
                projectTask("APPROVED", null, PROJECT_A, "Alpha"),
                projectTask("DONE", null, PROJECT_A, "Alpha"),
                projectTask("DONE", null, PROJECT_B, "Beta")), TODAY);

        assertThat(projects).hasSize(2);
        ProjectTaskCountsDTO alpha = projects.get(0);
        assertThat(alpha.getProjectId()).isEqualTo(PROJECT_A);
        assertThat(alpha.getProject()).isEqualTo("Alpha");
        assertThat(alpha.getInProgress()).isEqualTo(1);
        assertThat(alpha.getSnoozed()).isEqualTo(1);
        assertThat(alpha.getOpen()).isEqualTo(3);
        assertThat(alpha.getDone()).isEqualTo(1);
        assertThat(projects.get(1).getDone()).isEqualTo(1);
    }

    @Test
    void overdueCountsUnfinishedTasksPastTheirDeadline() {
        List<ProjectTaskCountsDTO> projects = ManagerKpisService.countByProject(List.of(
                projectTask("IN_PROGRESS", "20/10/2026", PROJECT_A, "Alpha"),  // overdue (yesterday)
                projectTask("NEW", "21/10/2026", PROJECT_A, "Alpha"),          // due today: not overdue yet
                projectTask("DONE", "01/10/2026", PROJECT_A, "Alpha")), TODAY); // done: never overdue

        assertThat(projects.get(0).getOverdue()).isEqualTo(1);
    }

    @Test
    void rejectedAndCancelledTasksAreNotCounted() {
        List<ProjectTaskCountsDTO> projects = ManagerKpisService.countByProject(List.of(
                projectTask("REJECTED", null, PROJECT_A, "Alpha"),
                projectTask("CANCELLED", null, PROJECT_A, "Alpha")), TODAY);

        assertThat(projects).isEmpty();
    }

    @Test
    void tasksWithoutAProjectGetTheirOwnRowAndProjectsAreSortedByName() {
        List<ProjectTaskCountsDTO> projects = ManagerKpisService.countByProject(List.of(
                projectTask("NEW", null, PROJECT_B, "beta"),
                projectTask("NEW", null, null, null),
                projectTask("NEW", null, PROJECT_C, "Zeta"),
                projectTask("NEW", null, PROJECT_A, "Alpha")), TODAY);

        assertThat(projects).extracting(ProjectTaskCountsDTO::getProject)
                .containsExactly("Alpha", "beta", "Zeta", ManagerKpisService.NO_PROJECT);
        assertThat(projects.get(3).getProjectId()).isNull();
    }

    // --- projectCards: the Projects screen ---

    private static TaskDTO cardTask(String status, Boolean available, String dueDate, String updatedAt) {
        TaskDTO task = projectTask(status, dueDate, PROJECT_A, "Alpha");
        task.setIsAvailable(available);
        task.setUpdatedAt(updatedAt);
        return task;
    }

    @Test
    void projectCardCountsProgressInProgressAndBlocked() {
        List<ManagerProjectDTO> cards = ManagerKpisService.projectCards(List.of(
                cardTask("DONE", true, null, "01/10/2026 09:00"),
                cardTask("DONE", true, null, "02/10/2026 09:00"),
                cardTask("IN_PROGRESS", true, null, "20/10/2026 14:30"),   // latest update
                cardTask("NEW", false, null, "03/10/2026 09:00"),          // blocked
                cardTask("CANCELLED", false, null, "21/10/2026 09:00")),   // not counted at all
                TODAY);

        assertThat(cards).hasSize(1);
        ManagerProjectDTO alpha = cards.get(0);
        assertThat(alpha.getId()).isEqualTo(PROJECT_A);
        assertThat(alpha.getName()).isEqualTo("Alpha");
        assertThat(alpha.getTotalCount()).isEqualTo(4);
        assertThat(alpha.getProgressPercent()).isEqualTo(50);
        assertThat(alpha.getInProgressCount()).isEqualTo(1);
        assertThat(alpha.getBlockedCount()).isEqualTo(1);
        assertThat(alpha.getTeam()).isEqualTo(ManagerKpisService.NO_TEAM);
        assertThat(alpha.getOwner()).isEqualTo(ManagerKpisService.UNASSIGNED);
        assertThat(alpha.getMembers()).isEmpty();
        // 20/10/2026 14:30 Israel time (UTC+3) = 11:30 UTC
        assertThat(alpha.getLastUpdatedAt()).isEqualTo("2026-10-20T11:30:00Z");
    }

    @Test
    void healthFollowsTheShareOfOpenTasksThatAreBlockedOrOverdue() {
        // 1 of 5 open at risk = 20% -> healthy
        assertThat(health(1, 5)).isEqualTo("healthy");
        // 2 of 5 = 40% -> at risk
        assertThat(health(2, 5)).isEqualTo("warning");
        // 3 of 5 = 60% -> critical
        assertThat(health(3, 5)).isEqualTo("critical");
    }

    /** One project with `open` open tasks, `late` of them overdue (yesterday). */
    private static String health(int late, int open) {
        List<TaskDTO> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < open; i++) {
            tasks.add(cardTask("NEW", true, i < late ? "20/10/2026" : "30/10/2026", null));
        }
        return ManagerKpisService.projectCards(tasks, TODAY).get(0).getStatus();
    }

    @Test
    void tasksWithoutAProjectAreNotAProjectCard() {
        assertThat(ManagerKpisService.projectCards(List.of(projectTask("NEW", null, null, null)), TODAY)).isEmpty();
    }

    // --- filterTasks: the Work in progress and Blocked & delayed screens ---

    @Test
    void inProgressViewKeepsOnlyInProgressTasks() {
        List<TaskDTO> tasks = ManagerKpisService.filterTasks(List.of(
                task("IN_PROGRESS", true, null, PROJECT_A),
                task("NEW", true, null, PROJECT_A),
                task("DONE", true, null, PROJECT_A)), ManagerKpisService.TaskView.IN_PROGRESS, TODAY);

        assertThat(tasks).extracting(TaskDTO::getStatus).containsExactly("IN_PROGRESS");
    }

    @Test
    void blockedOrDelayedViewKeepsOpenTasksThatAreBlockedOrLate() {
        List<TaskDTO> tasks = ManagerKpisService.filterTasks(List.of(
                task("APPROVED", false, null, PROJECT_A),          // blocked
                task("NEW", true, "20/10/2026", PROJECT_A),        // delayed (yesterday)
                task("NEW", true, "21/10/2026", PROJECT_A),        // due today: neither
                task("DONE", false, "01/10/2026", PROJECT_A),      // closed: never listed
                task("CANCELLED", false, null, PROJECT_A)),        // closed: never listed
                ManagerKpisService.TaskView.BLOCKED_OR_DELAYED, TODAY);

        assertThat(tasks).extracting(TaskDTO::getId)
                .containsExactlyInAnyOrder("APPROVED-null", "NEW-20/10/2026");
    }

    @Test
    void tasksAreSortedBySoonestDeadlineWithNoDeadlineLast() {
        List<TaskDTO> tasks = ManagerKpisService.filterTasks(List.of(
                task("IN_PROGRESS", true, null, PROJECT_A),
                task("IN_PROGRESS", true, "30/10/2026", PROJECT_A),
                task("IN_PROGRESS", true, "01/10/2026", PROJECT_A)), ManagerKpisService.TaskView.IN_PROGRESS, TODAY);

        assertThat(tasks).extracting(TaskDTO::getDueDate).containsExactly("01/10/2026", "30/10/2026", null);
    }

    @Test
    void getTasksInboxDownBecomesServiceUnavailable() {
        when(inboxClient.getTenantTasks("mgr_001")).thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> service.getTasks("mgr_001", ManagerKpisService.TaskView.IN_PROGRESS))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void getProjectTaskCountsUsesTheActorsTenantTasks() {
        when(inboxClient.getTenantTasks("mgr_001")).thenReturn(List.of(projectTask("DONE", null, PROJECT_A, "Alpha")));

        assertThat(service.getProjectTaskCounts("mgr_001")).hasSize(1);
    }

    @Test
    void getProjectTaskCountsInboxDownBecomesServiceUnavailable() {
        when(inboxClient.getTenantTasks("mgr_001")).thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> service.getProjectTaskCounts("mgr_001"))
                .isInstanceOf(ServiceUnavailableException.class);
    }
}
