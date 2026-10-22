package com.rnd.inbox.workinsights;

import java.time.LocalDate;

public record AnalysisPeriods(DateRange current, DateRange previous) {

    public record DateRange(LocalDate from, LocalDate to) {
    }

    public static AnalysisPeriods endingAt(LocalDate lastDay, int windowDays) {
        LocalDate currentFrom = lastDay.minusDays(windowDays - 1L);
        DateRange current = new DateRange(currentFrom, lastDay);
        DateRange previous = new DateRange(currentFrom.minusDays(windowDays), currentFrom.minusDays(1));
        return new AnalysisPeriods(current, previous);
    }
}
