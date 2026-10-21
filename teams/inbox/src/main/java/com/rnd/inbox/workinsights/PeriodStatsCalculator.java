package com.rnd.inbox.workinsights;

import com.rnd.inbox.workinsights.dto.EffortBucketStatsDto;
import com.rnd.inbox.workinsights.dto.PeriodStatsDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class PeriodStatsCalculator {

    private static final long MINUTES_PER_HOUR = 60;

    private final WorkInsightsProperties properties;

    public PeriodStatsCalculator(WorkInsightsProperties properties) {
        this.properties = properties;
    }

    public PeriodStatsDto calculate(List<CompletedTaskRow> rows) {
        List<CompletedTaskRow> rowsWithDeadline = rows.stream()
                .filter(row -> row.deadline() != null)
                .toList();
        List<Long> hoursLateOfLateRows = rowsWithDeadline.stream()
                .filter(PeriodStatsCalculator::isLate)
                .map(PeriodStatsCalculator::hoursLate)
                .toList();

        long completedWithDeadline = rowsWithDeadline.size();
        long completedLate = hoursLateOfLateRows.size();
        long completedOnTime = completedWithDeadline - completedLate;

        return new PeriodStatsDto(
                rows.size(),
                completedWithDeadline,
                completedOnTime,
                completedLate,
                onTimeRate(completedOnTime, completedWithDeadline),
                median(hoursLateOfLateRows),
                bucketStats(rowsWithDeadline));
    }

    Map<EffortBucket, EffortBucketStatsDto> bucketStats(List<CompletedTaskRow> rowsWithDeadline) {
        Map<EffortBucket, List<CompletedTaskRow>> rowsByBucket = rowsWithDeadline.stream()
                .collect(Collectors.groupingBy(this::bucketOf));

        Map<EffortBucket, EffortBucketStatsDto> stats = new EnumMap<>(EffortBucket.class);
        for (EffortBucket bucket : EffortBucket.values()) {
            List<CompletedTaskRow> rows = rowsByBucket.getOrDefault(bucket, List.of());
            long late = rows.stream().filter(PeriodStatsCalculator::isLate).count();
            stats.put(bucket, new EffortBucketStatsDto(rows.size(), late, lateRate(late, rows.size())));
        }
        return stats;
    }

    private EffortBucket bucketOf(CompletedTaskRow row) {
        int minutes = row.effortEstimateMinutes() != null
                ? row.effortEstimateMinutes()
                : properties.getDefaultEffortMinutes();
        return EffortBucket.fromMinutes(minutes, properties.getShortMaxMinutes(), properties.getMediumMaxMinutes());
    }

    static boolean isLate(CompletedTaskRow row) {
        return row.deadline() != null && minutesLate(row) >= 1;
    }

    static long hoursLate(CompletedTaskRow row) {
        return Math.ceilDiv(minutesLate(row), MINUTES_PER_HOUR);
    }

    private static long minutesLate(CompletedTaskRow row) {
        return Duration.between(row.deadline(), row.completedAt()).toMinutes();
    }

    static Double onTimeRate(long onTime, long total) {
        return rate(onTime, total, RoundingMode.DOWN);
    }

    static Double lateRate(long late, long total) {
        return rate(late, total, RoundingMode.UP);
    }

    private static Double rate(long part, long total, RoundingMode roundingMode) {
        if (total == 0) {
            return null;
        }
        return BigDecimal.valueOf(part)
                .divide(BigDecimal.valueOf(total), 4, roundingMode)
                .doubleValue();
    }

    static Long median(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return Math.round((sorted.get(middle - 1) + sorted.get(middle)) / 2.0);
    }
}
