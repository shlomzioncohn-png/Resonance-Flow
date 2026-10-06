package com.rnd.bridge.task.enrichment;

/**
 * Default enricher used when enrichment is disabled or the source is not
 * supported. Always returns an empty result.
 */
public class NoOpTaskEnricher implements TaskEnricher {

    @Override
    public EnrichmentOutput enrich(EnrichmentInput input) {
        return EnrichmentOutput.empty();
    }
}
