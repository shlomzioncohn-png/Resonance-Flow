package com.rnd.bridge.enrichment;

import java.time.Instant;

/**
 * A due date the LLM read out of free text (e.g. "let's close this by
 * Friday"), never Bridge's own computation - see {@link EnrichmentService}
 * for why this never touches {@code Task.deadline} directly.
 *
 * @param dueDate    null when no date-like phrase was found - not "absent",
 *                   which would make this indistinguishable from "not asked".
 * @param confidence 0.0..1.0. 0 for "found nothing", matching {@link
 *                   NoOpEnrichmentService}'s contract - never null, so a
 *                   caller can sort/filter on it without a null check.
 * @param rationale  the phrase or reasoning the extraction came from, so a
 *                   human reviewing the suggestion can tell why the LLM
 *                   thinks this is the date, e.g. the literal source text.
 */
public record DueDateSuggestion(Instant dueDate, double confidence, String rationale) {
}
