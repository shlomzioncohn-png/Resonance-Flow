package com.rnd.inbox.workinsights;

import com.rnd.inbox.workinsights.AnalysisPeriods.DateRange;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisPeriodsTest {

    @Test
    void endingAt_withThirtyDayWindow_returnsThirtyDaysEndingOnLastDay() {
        AnalysisPeriods periods = AnalysisPeriods.endingAt(LocalDate.of(2026, 10, 6), 30);

        assertThat(periods.current())
                .isEqualTo(new DateRange(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 10, 6)));
    }

    @Test
    void endingAt_withThirtyDayWindow_returnsPreviousThirtyDaysRightBeforeCurrent() {
        AnalysisPeriods periods = AnalysisPeriods.endingAt(LocalDate.of(2026, 10, 6), 30);

        assertThat(periods.previous())
                .isEqualTo(new DateRange(LocalDate.of(2026, 8, 8), LocalDate.of(2026, 9, 6)));
    }

    @Test
    void endingAt_withOneDayWindow_returnsLastDayAndTheDayBeforeIt() {
        AnalysisPeriods periods = AnalysisPeriods.endingAt(LocalDate.of(2026, 10, 6), 1);

        assertThat(periods.current())
                .isEqualTo(new DateRange(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 6)));
        assertThat(periods.previous())
                .isEqualTo(new DateRange(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5)));
    }

    @Test
    void endingAt_whenWindowCrossesYearBoundary_returnsDatesInBothYears() {
        AnalysisPeriods periods = AnalysisPeriods.endingAt(LocalDate.of(2027, 1, 10), 30);

        assertThat(periods.current())
                .isEqualTo(new DateRange(LocalDate.of(2026, 12, 12), LocalDate.of(2027, 1, 10)));
        assertThat(periods.previous())
                .isEqualTo(new DateRange(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 12, 11)));
    }
}
