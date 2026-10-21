package com.rnd.inbox.workinsights;

import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.task.assignment.AssignmentRole;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.repository.TaskRepository;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.repository.UserRepository;
import com.rnd.inbox.workinsights.dto.WorkInsightsDataDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class WorkInsightsDataService {

    private static final Set<AssignmentRole> PERSONAL_ROLES =
            EnumSet.of(AssignmentRole.OWNER, AssignmentRole.ASSIGNEE, AssignmentRole.RESPONSIBLE);
    private static final Set<TaskStatus> OPEN_STATUSES =
            EnumSet.complementOf(EnumSet.of(TaskStatus.DONE, TaskStatus.REJECTED, TaskStatus.CANCELLED));

    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final PeriodStatsCalculator periodStatsCalculator;
    private final OpenNowCalculator openNowCalculator;
    private final WorkInsightsProperties properties;
    private final Clock clock;

    public WorkInsightsDataService(TaskRepository taskRepository,
                                   UserRepository userRepository,
                                   PeriodStatsCalculator periodStatsCalculator,
                                   OpenNowCalculator openNowCalculator,
                                   WorkInsightsProperties properties,
                                   Clock clock) {
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
        this.periodStatsCalculator = periodStatsCalculator;
        this.openNowCalculator = openNowCalculator;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public WorkInsightsDataDto getWorkInsightsData(UUID actorId) {
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new UnauthenticatedException("Actor " + actorId + " does not exist"));
        UUID tenantId = actor.getTenant().getId();

        Instant now = Instant.now(clock);
        LocalDate yesterday = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(1);
        AnalysisPeriods periods = AnalysisPeriods.endingAt(yesterday, properties.getWindowDays());
        Instant previousStart = startOfDay(periods.previous().from());
        Instant currentStart = startOfDay(periods.current().from());
        Instant currentEnd = startOfDay(periods.current().to().plusDays(1));

        List<CompletedTaskRow> completed = taskRepository.findCompletedForUser(
                tenantId, actorId, PERSONAL_ROLES, TaskStatus.DONE, previousStart, currentEnd);
        Map<Boolean, List<CompletedTaskRow>> completedByIsCurrent = completed.stream()
                .collect(Collectors.partitioningBy(row -> !row.completedAt().isBefore(currentStart)));
        List<Instant> openDeadlines =
                taskRepository.findOpenDeadlinesForUser(tenantId, actorId, PERSONAL_ROLES, OPEN_STATUSES);

        return new WorkInsightsDataDto(
                periods,
                periodStatsCalculator.calculate(completedByIsCurrent.get(true)),
                periodStatsCalculator.calculate(completedByIsCurrent.get(false)),
                openNowCalculator.calculate(openDeadlines, now));
    }

    private static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
