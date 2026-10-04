package com.rnd.bridge.task.enrichment;

import java.time.Instant;

/**
 * What the enricher inferred from the input. Null fields mean "no change".
 */
public record EnrichmentOutput(
        Integer urgency,
        Double confidence,
        String title,
        String description,
        Instant deadline,
        Integer effortEstimateMinutes) {

    public static EnrichmentOutput empty() {
        return new EnrichmentOutput(null, null, null, null, null, null);
    }

    public boolean isEmpty() {
        return urgency == null
                && confidence == null
                && title == null
                && description == null
                && deadline == null
                && effortEstimateMinutes == null;
    }
}
