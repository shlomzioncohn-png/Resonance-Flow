package com.rnd.inbox.workinsights;

import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.task.assignment.AssignmentRole;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.repository.TaskRepository;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.repository.UserRepository;
import com.rnd.inbox.workinsights.dto.OpenNowDto;
import com.rnd.inbox.workinsights.dto.WorkInsightsDataDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkInsightsDataServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");
    private static final Instant PREVIOUS_START = Instant.parse("2026-08-08T00:00:00Z");
    private static final Instant CURRENT_START = Instant.parse("2026-09-07T00:00:00Z");
    private static final Instant CURRENT_END = Instant.parse("2026-10-07T00:00:00Z");
    private static final Set<AssignmentRole> ROLES =
            EnumSet.of(AssignmentRole.OWNER, AssignmentRole.ASSIGNEE, AssignmentRole.RESPONSIBLE);
    private static final Set<TaskStatus> OPEN_STATUSES = EnumSet.of(
            TaskStatus.NEW, TaskStatus.IN_REVIEW, TaskStatus.APPROVED, TaskStatus.IN_PROGRESS, TaskStatus.SNOOZED);

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private User actor;

    @Mock
    private Tenant tenant;

    private WorkInsightsDataService service;

    @BeforeEach
    void setUp() {
        WorkInsightsProperties properties = new WorkInsightsProperties();
        service = new WorkInsightsDataService(taskRepository, userRepository,
                new PeriodStatsCalculator(properties), new OpenNowCalculator(properties), properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void getWorkInsightsData_returnsPeriodsEndingYesterdayAndSplitsCompletedTasksBetweenThem() {
        when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.of(actor));
        when(actor.getTenant()).thenReturn(tenant);
        when(tenant.getId()).thenReturn(TENANT_ID);
        when(taskRepository.findCompletedForUser(
                TENANT_ID, ACTOR_ID, ROLES, TaskStatus.DONE, PREVIOUS_START, CURRENT_END))
                .thenReturn(List.of(
                        completedAt(CURRENT_START.minusSeconds(1)),
                        completedAt(CURRENT_START),
                        completedAt(CURRENT_END.minusSeconds(1))));
        when(taskRepository.findOpenDeadlinesForUser(TENANT_ID, ACTOR_ID, ROLES, OPEN_STATUSES))
                .thenReturn(List.of(NOW.plus(Duration.ofDays(1))));

        WorkInsightsDataDto data = service.getWorkInsightsData(ACTOR_ID);

        assertThat(data.period()).isEqualTo(AnalysisPeriods.endingAt(LocalDate.of(2026, 10, 6), 30));
        assertThat(data.currentPeriod().tasksCompleted()).isEqualTo(2);
        assertThat(data.previousPeriod().tasksCompleted()).isEqualTo(1);
        assertThat(data.openNow()).isEqualTo(new OpenNowDto(1, 0, 1, 7));
    }

    @Test
    void getWorkInsightsData_whenActorDoesNotExist_throwsUnauthenticatedException() {
        when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getWorkInsightsData(ACTOR_ID))
                .isInstanceOf(UnauthenticatedException.class);
    }

    private static CompletedTaskRow completedAt(Instant completedAt) {
        return new CompletedTaskRow(completedAt, null, null);
    }
}
