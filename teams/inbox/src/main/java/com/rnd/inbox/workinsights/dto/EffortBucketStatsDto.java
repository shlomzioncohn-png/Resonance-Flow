package com.rnd.inbox.workinsights.dto;

public record EffortBucketStatsDto(long completedWithDeadline, long completedLate, Double lateRate) {
}
