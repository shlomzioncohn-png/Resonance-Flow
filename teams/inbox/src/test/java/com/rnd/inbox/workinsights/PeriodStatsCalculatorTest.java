package com.rnd.inbox.workinsights;

import com.rnd.inbox.workinsights.dto.EffortBucketStatsDto;
import com.rnd.inbox.workinsights.dto.PeriodStatsDto;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class PeriodStatsCalculatorTest {

    private static final Instant DEADLINE = Instant.parse("2026-09-20T12:00:00Z");

    private final PeriodStatsCalculator calculator = new PeriodStatsCalculator(new WorkInsightsProperties());

    @Test
    void isLate_whenCompletedAFullMinuteAfterDeadline_returnsTrue() {
        assertThat(PeriodStatsCalculator.isLate(completed(DEADLINE.plusSeconds(60)))).isTrue();
    }

    @Test
    void isLate_whenCompletedLessThanAMinuteAfterDeadline_returnsFalse() {
        assertThat(PeriodStatsCalculator.isLate(completed(DEADLINE.plusSeconds(59)))).isFalse();
    }

    @Test
    void isLate_whenCompletedExactlyAtDeadline_returnsFalse() {
        assertThat(PeriodStatsCalculator.isLate(completed(DEADLINE))).isFalse();
    }

    @Test
    void isLate_whenCompletedBeforeDeadline_returnsFalse() {
        assertThat(PeriodStatsCalculator.isLate(completed(DEADLINE.minus(Duration.ofHours(3))))).isFalse();
    }

    @Test
    void isLate_whenTaskHasNoDeadline_returnsFalse() {
        assertThat(PeriodStatsCalculator.isLate(new CompletedTaskRow(DEADLINE, null, null))).isFalse();
    }

    @Test
    void hoursLate_whenLateByWholeHours_returnsThem() {
        assertThat(PeriodStatsCalculator.hoursLate(completed(DEADLINE.plus(Duration.ofHours(14))))).isEqualTo(14);
    }

    @Test
    void hoursLate_whenLateByLessThanAnHour_returnsOne() {
        assertThat(PeriodStatsCalculator.hoursLate(completed(DEADLINE.plus(Duration.ofMinutes(20))))).isEqualTo(1);
    }

    @Test
    void hoursLate_whenLateByPartOfAnHourMore_roundsUp() {
        assertThat(PeriodStatsCalculator.hoursLate(completed(DEADLINE.plus(Duration.ofMinutes(121))))).isEqualTo(3);
    }

    @Test
    void bucketStats_countsRowsAndLateRowsInEachBucket() {
        List<CompletedTaskRow> rows = List.of(
                onTime(20), onTime(30),
                onTime(90), late(45), onTime(120), onTime(60), onTime(100),
                late(300), late(121));

        assertThat(calculator.bucketStats(rows)).containsExactly(
                entry(EffortBucket.SHORT, new EffortBucketStatsDto(2, 0, 0.0)),
                entry(EffortBucket.MEDIUM, new EffortBucketStatsDto(5, 1, 0.2)),
                entry(EffortBucket.LONG, new EffortBucketStatsDto(2, 2, 1.0)));
    }

    @Test
    void bucketStats_whenBucketHasNoRows_returnsItWithZerosAndNullRate() {
        assertThat(calculator.bucketStats(List.of(late(20)))).containsExactly(
                entry(EffortBucket.SHORT, new EffortBucketStatsDto(1, 1, 1.0)),
                entry(EffortBucket.MEDIUM, new EffortBucketStatsDto(0, 0, null)),
                entry(EffortBucket.LONG, new EffortBucketStatsDto(0, 0, null)));
    }

    @Test
    void bucketStats_whenRowHasNoEffortEstimate_countsItByDefaultEffort() {
        WorkInsightsProperties properties = new WorkInsightsProperties();
        properties.setDefaultEffortMinutes(60);
        PeriodStatsCalculator mediumByDefault = new PeriodStatsCalculator(properties);

        assertThat(mediumByDefault.bucketStats(List.of(onTime(null))))
                .containsEntry(EffortBucket.SHORT, new EffortBucketStatsDto(0, 0, null))
                .containsEntry(EffortBucket.MEDIUM, new EffortBucketStatsDto(1, 0, 0.0));
    }

    @Test
    void calculate_withMixedRows_returnsAllStatsOfThePeriod() {
        List<CompletedTaskRow> rows = List.of(
                withoutDeadline(), withoutDeadline(), withoutDeadline(),
                onTime(10), onTime(15), onTime(20), onTime(25), onTime(30), onTime(null),
                onTime(45), onTime(60), onTime(90), onTime(120), lateBy(100, 3),
                lateBy(180, 10), lateBy(240, 14), lateBy(300, 20), lateBy(480, 30));

        PeriodStatsDto stats = calculator.calculate(rows);

        assertThat(stats.tasksCompleted()).isEqualTo(18);
        assertThat(stats.completedWithDeadline()).isEqualTo(15);
        assertThat(stats.completedOnTime()).isEqualTo(10);
        assertThat(stats.completedLate()).isEqualTo(5);
        assertThat(stats.onTimeRate()).isEqualTo(0.6666);
        assertThat(stats.medianHoursLate()).isEqualTo(14L);
        assertThat(stats.byEstimatedEffort()).containsExactly(
                entry(EffortBucket.SHORT, new EffortBucketStatsDto(6, 0, 0.0)),
                entry(EffortBucket.MEDIUM, new EffortBucketStatsDto(5, 1, 0.2)),
                entry(EffortBucket.LONG, new EffortBucketStatsDto(4, 4, 1.0)));
    }

    @Test
    void calculate_whenNoRows_returnsZerosAndNullRates() {
        PeriodStatsDto stats = calculator.calculate(List.of());

        assertThat(stats.tasksCompleted()).isZero();
        assertThat(stats.completedWithDeadline()).isZero();
        assertThat(stats.completedOnTime()).isZero();
        assertThat(stats.completedLate()).isZero();
        assertThat(stats.onTimeRate()).isNull();
        assertThat(stats.medianHoursLate()).isNull();
        assertThat(stats.byEstimatedEffort()).containsOnlyKeys(EffortBucket.values());
    }

    @Test
    void calculate_whenNothingIsLate_returnsFullOnTimeRateAndNullMedian() {
        PeriodStatsDto stats = calculator.calculate(List.of(onTime(20), onTime(200)));

        assertThat(stats.completedLate()).isZero();
        assertThat(stats.onTimeRate()).isEqualTo(1.0);
        assertThat(stats.medianHoursLate()).isNull();
    }

    @Test
    void calculate_whenRowHasNoDeadline_countsItOnlyInTasksCompleted() {
        PeriodStatsDto stats = calculator.calculate(List.of(withoutDeadline()));

        assertThat(stats.tasksCompleted()).isEqualTo(1);
        assertThat(stats.completedWithDeadline()).isZero();
        assertThat(stats.byEstimatedEffort().get(EffortBucket.SHORT))
                .isEqualTo(new EffortBucketStatsDto(0, 0, null));
    }

    private static CompletedTaskRow withoutDeadline() {
        return new CompletedTaskRow(DEADLINE, null, 20);
    }

    private static CompletedTaskRow lateBy(Integer effortEstimateMinutes, long hours) {
        return new CompletedTaskRow(DEADLINE.plus(Duration.ofHours(hours)), DEADLINE, effortEstimateMinutes);
    }

    private static CompletedTaskRow completed(Instant completedAt) {
        return new CompletedTaskRow(completedAt, DEADLINE, null);
    }

    private static CompletedTaskRow onTime(Integer effortEstimateMinutes) {
        return new CompletedTaskRow(DEADLINE.minus(Duration.ofHours(1)), DEADLINE, effortEstimateMinutes);
    }

    private static CompletedTaskRow late(Integer effortEstimateMinutes) {
        return new CompletedTaskRow(DEADLINE.plus(Duration.ofHours(5)), DEADLINE, effortEstimateMinutes);
    }

    @Test
    void onTimeRate_whenTotalIsZero_returnsNull() {
        assertThat(PeriodStatsCalculator.onTimeRate(0, 0)).isNull();
    }

    @Test
    void lateRate_whenTotalIsZero_returnsNull() {
        assertThat(PeriodStatsCalculator.lateRate(0, 0)).isNull();
    }

    @Test
    void onTimeRate_whenResultHasManyDecimals_roundsDownToFour() {
        assertThat(PeriodStatsCalculator.onTimeRate(10, 15)).isEqualTo(0.6666);
    }

    @Test
    void lateRate_whenResultHasManyDecimals_roundsUpToFour() {
        assertThat(PeriodStatsCalculator.lateRate(10, 15)).isEqualTo(0.6667);
    }

    @Test
    void onTimeRate_whenOneOfTwoHundredIsLate_staysBelowOne() {
        assertThat(PeriodStatsCalculator.onTimeRate(199, 200)).isEqualTo(0.995);
    }

    @Test
    void onTimeRate_whenOneOfManyIsLate_neverReachesOne() {
        assertThat(PeriodStatsCalculator.onTimeRate(99_999, 100_000)).isLessThan(1.0);
    }

    @Test
    void lateRate_whenOneOfThousandIsLate_staysAboveZero() {
        assertThat(PeriodStatsCalculator.lateRate(1, 1000)).isEqualTo(0.001);
    }

    @Test
    void lateRate_whenOneOfManyIsLate_neverDropsToZero() {
        assertThat(PeriodStatsCalculator.lateRate(1, 100_000)).isGreaterThan(0.0);
    }

    @Test
    void onTimeRate_whenPartEqualsTotal_returnsOne() {
        assertThat(PeriodStatsCalculator.onTimeRate(4, 4)).isEqualTo(1.0);
    }

    @Test
    void lateRate_whenPartIsZero_returnsZero() {
        assertThat(PeriodStatsCalculator.lateRate(0, 6)).isEqualTo(0.0);
    }

    @Test
    void median_whenEmpty_returnsNull() {
        assertThat(PeriodStatsCalculator.median(List.of())).isNull();
    }

    @Test
    void median_whenOddCount_returnsMiddleOfSortedValues() {
        assertThat(PeriodStatsCalculator.median(List.of(30L, 2L, 14L, 5L, 21L))).isEqualTo(14L);
    }

    @Test
    void median_whenEvenCount_returnsRoundedAverageOfTwoMiddleValues() {
        assertThat(PeriodStatsCalculator.median(List.of(20L, 3L, 11L, 14L))).isEqualTo(13L);
    }

    @Test
    void median_whenSingleValue_returnsIt() {
        assertThat(PeriodStatsCalculator.median(List.of(7L))).isEqualTo(7L);
    }
}
