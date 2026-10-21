package com.rnd.inbox.workinsights;

import com.rnd.inbox.workinsights.dto.OpenNowDto;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenNowCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");

    private final OpenNowCalculator calculator = new OpenNowCalculator(new WorkInsightsProperties());

    @Test
    void calculate_countsOpenOverdueAndDueWithinSevenDays() {
        List<Instant> openDeadlines = Arrays.asList(
                null,
                NOW.minus(Duration.ofDays(2)),
                NOW.minus(Duration.ofMinutes(1)),
                NOW.minusSeconds(59),
                NOW.plus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(7)),
                NOW.plus(Duration.ofDays(7)).plusSeconds(1),
                NOW.plus(Duration.ofDays(30)));

        assertThat(calculator.calculate(openDeadlines, NOW)).isEqualTo(new OpenNowDto(8, 2, 3, 7));
    }

    @Test
    void calculate_usesDueSoonDaysFromProperties() {
        WorkInsightsProperties properties = new WorkInsightsProperties();
        properties.setDueSoonDays(3);
        List<Instant> openDeadlines = List.of(NOW.plus(Duration.ofDays(3)), NOW.plus(Duration.ofDays(4)));

        assertThat(new OpenNowCalculator(properties).calculate(openDeadlines, NOW))
                .isEqualTo(new OpenNowDto(2, 0, 1, 3));
    }

    @Test
    void calculate_whenNoOpenTasks_returnsZeros() {
        assertThat(calculator.calculate(List.of(), NOW)).isEqualTo(new OpenNowDto(0, 0, 0, 7));
    }

    @Test
    void calculate_whenTaskHasNoDeadline_countsItOnlyAsOpen() {
        assertThat(calculator.calculate(Arrays.asList((Instant) null), NOW)).isEqualTo(new OpenNowDto(1, 0, 0, 7));
    }
}
