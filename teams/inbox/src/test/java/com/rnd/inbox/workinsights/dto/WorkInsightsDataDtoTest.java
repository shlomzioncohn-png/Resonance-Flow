package com.rnd.inbox.workinsights.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rnd.inbox.workinsights.AnalysisPeriods;
import com.rnd.inbox.workinsights.PeriodStatsCalculator;
import com.rnd.inbox.workinsights.WorkInsightsProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkInsightsDataDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void serialization_writesSnakeCaseFieldsIsoDatesAndLowercaseEffortBuckets() {
        PeriodStatsDto emptyStats = new PeriodStatsCalculator(new WorkInsightsProperties()).calculate(List.of());
        WorkInsightsDataDto data = new WorkInsightsDataDto(
                AnalysisPeriods.endingAt(LocalDate.of(2026, 10, 6), 30),
                emptyStats,
                emptyStats,
                new OpenNowDto(7, 2, 3, 7));

        JsonNode json = objectMapper.valueToTree(data);

        assertThat(json.at("/period/current/from").asText()).isEqualTo("2026-09-07");
        assertThat(json.at("/period/previous/to").asText()).isEqualTo("2026-09-06");
        assertThat(json.at("/current_period/tasks_completed").asLong()).isZero();
        assertThat(json.at("/current_period/on_time_rate").isNull()).isTrue();
        assertThat(json.at("/previous_period/by_estimated_effort").fieldNames())
                .toIterable().containsExactly("short", "medium", "long");
        assertThat(json.at("/open_now/due_soon").asLong()).isEqualTo(3);
        assertThat(json.at("/open_now/due_soon_days").asInt()).isEqualTo(7);
    }
}
