package com.rnd.bridge.enrichment;

/**
 * The default {@link EnrichmentService}: no LLM provider is wired yet (the
 * choice of vendor is still open), so this always returns an empty result -
 * never a made-up suggestion, never a thrown exception. Every field this
 * lane produces is confidence-scored, so "no provider configured" and
 * "provider found nothing" look identical to a caller: an empty {@link
 * TaskEnrichment}.
 * <p>
 * Mirrors this codebase's existing default/profile-override convention
 * (e.g. {@code JsonDealRepository} vs. {@code hubspot}-profile
 * {@code HubSpotDealRepository}, see {@code CrmLaneConfig}) - a real
 * provider (Anthropic/OpenAI) becomes a {@code @Profile}-gated bean later,
 * this class is untouched by that decision.
 */
public final class NoOpEnrichmentService implements EnrichmentService {

    @Override
    public TaskEnrichment enrich(String sourceRef, String freeText) {
        return TaskEnrichment.empty(sourceRef);
    }
}
