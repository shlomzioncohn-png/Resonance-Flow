package com.rnd.bridge.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

public record Task(
        String id,
        String orgId,
        String title,
        String description,
        TaskStatus status,
        Double priorityScore,
        Double confidenceScore,
        SourceType sourceType,
        String sourceRefId,
        String externalId,
        Integer estimatedDurationMinutes,
        LocalDate dueDate,
        Map<String, Object> metadata,
        Long version,
        String ownerId,
        String dependencyTaskId,
        Instant createdAt,
        Instant updatedAt) {
}