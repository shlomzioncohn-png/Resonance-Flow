package com.rnd.bridge.task.enrichment;

import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.TaskStatus;

import java.time.Instant;

/**
 * The ETL values already extracted from a source record, passed to the
 * enricher so it can decide what to fill in.
 */
public record EnrichmentInput(
        SourceType sourceType,
        String title,
        String description,
        TaskStatus status,
        String sourceRef,
        Instant asOf,
        Integer urgency,
        Instant deadline) {
}
