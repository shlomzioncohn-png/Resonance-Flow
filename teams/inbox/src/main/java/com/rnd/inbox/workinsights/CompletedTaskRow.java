package com.rnd.inbox.workinsights;

import java.time.Instant;

public record CompletedTaskRow(Instant completedAt, Instant deadline, Integer effortEstimateMinutes) {
}
