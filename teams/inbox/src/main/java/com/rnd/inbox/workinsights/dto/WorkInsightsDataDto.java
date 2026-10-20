package com.rnd.inbox.workinsights.dto;

import com.rnd.inbox.workinsights.AnalysisPeriods;

public record WorkInsightsDataDto(AnalysisPeriods period,
                                  PeriodStatsDto currentPeriod,
                                  PeriodStatsDto previousPeriod,
                                  OpenNowDto openNow) {
}
