package com.rnd.bff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rnd.bff.client.InboxClient;
import com.rnd.bff.dto.ManagerKpisDTO;
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
}
