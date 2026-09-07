package com.rnd.bridge.enrichment;

/**
 * Reads free text from a source item (a CRM deal description, a GitHub PR
 * body, ...) and returns whatever due date / severity / dependency hints an
 * LLM can find in it, each carrying its own confidence score - the same
 * confidence-scored-suggestion pattern Mind already uses for its own
 * LLM-inferred tasks (see {@code AbstractTaskMapper.confidence()} on the
 * Bridge side).
 * <p>
 * Never a source of truth: nothing an implementation returns is written
 * into {@code Task} by this lane, and a dependency hint goes to Chain as a
 * suggestion only - Bridge does not write the dependency graph itself.
 * <p>
 * Must be robust to weak input by contract, not by convention: a caller
 * passing null/blank text, or an implementation that finds nothing, never
 * throws - it returns {@link TaskEnrichment#empty(String)} (or a suggestion
 * with confidence 0) so callers never need a try/catch around this to keep
 * a sync running. No implementation may propagate a third-party
 * (LLM-provider) exception past this boundary.
 */
public interface EnrichmentService {

    TaskEnrichment enrich(String sourceRef, String freeText);
}
