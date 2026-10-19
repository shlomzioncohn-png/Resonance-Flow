package com.rnd.inbox.workinsights.dto;

import com.rnd.inbox.workinsights.EffortBucket;

import java.util.Map;

public record PeriodStatsDto(long tasksCompleted,
                             long completedWithDeadline,
                             long completedOnTime,
                             long completedLate,
                             Double onTimeRate,
                             Long medianHoursLate,
                             Map<EffortBucket, EffortBucketStatsDto> byEstimatedEffort) {
}
