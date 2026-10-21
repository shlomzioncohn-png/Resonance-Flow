package com.rnd.inbox.workinsights;

import com.rnd.inbox.workinsights.dto.OpenNowDto;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Component
public class OpenNowCalculator {

    private final WorkInsightsProperties properties;

    public OpenNowCalculator(WorkInsightsProperties properties) {
        this.properties = properties;
    }

    public OpenNowDto calculate(List<Instant> openDeadlines, Instant now) {
        List<Instant> deadlines = openDeadlines.stream().filter(Objects::nonNull).toList();
        int dueSoonDays = properties.getDueSoonDays();
        Instant dueSoonLimit = now.plus(Duration.ofDays(dueSoonDays));

        long overdue = deadlines.stream().filter(deadline -> isOverdue(deadline, now)).count();
        long dueSoon = deadlines.stream()
                .filter(deadline -> !isOverdue(deadline, now) && !deadline.isAfter(dueSoonLimit))
                .count();
        return new OpenNowDto(openDeadlines.size(), overdue, dueSoon, dueSoonDays);
    }

    private static boolean isOverdue(Instant deadline, Instant now) {
        return Duration.between(deadline, now).toMinutes() >= 1;
    }
}
