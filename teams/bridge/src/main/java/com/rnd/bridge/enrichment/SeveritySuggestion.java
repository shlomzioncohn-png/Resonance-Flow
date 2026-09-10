package com.rnd.bridge.enrichment;

/**
 * A severity the LLM read out of labels + free text (e.g. a "P0" label plus
 * "this is blocking the release" in the body).
 * <p>
 * Deliberately a free-text {@code label}, not {@link
 * com.rnd.bridge.task.model.Tier} - Tier is an org-scope axis (operational /
 * tactical / strategic), not a severity scale, and forcing "critical" or
 * "P0" onto it would be a made-up mapping nobody asked for. What Task field
 * (if any) a severity suggestion should ever promote into is not decided -
 * this stays a suggestion only.
 *
 * @param label      whatever severity token the source used, verbatim
 *                   (e.g. "P0", "critical", "minor") - null when none found.
 * @param confidence 0.0..1.0, 0 for "found nothing" - never null.
 * @param rationale  the label/phrase the extraction came from.
 */
public record SeveritySuggestion(String label, double confidence, String rationale) {
}
