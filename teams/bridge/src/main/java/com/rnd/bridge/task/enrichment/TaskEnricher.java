package com.rnd.bridge.task.enrichment;

/**
 * Enriches a task draft before the final {@code Task} is built.
 * Implementations must never throw; on any doubt they return
 * {@link EnrichmentOutput#empty()}.
 */
public interface TaskEnricher {

    EnrichmentOutput enrich(EnrichmentInput input);
}
