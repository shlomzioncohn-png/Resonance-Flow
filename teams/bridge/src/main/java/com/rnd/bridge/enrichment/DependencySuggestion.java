package com.rnd.bridge.enrichment;

/**
 * A dependency the LLM read out of free text, e.g. "blocks #123" or "Fixes
 * #45". Published to Chain as a suggestion only - Bridge does not write the
 * dependency graph itself, and {@link com.rnd.bridge.task.model.Task}
 * carries no dependency field for the same reason (see its class Javadoc:
 * {@code dependency_task_id} was removed on purpose).
 *
 * @param targetRef  the ref as the source wrote it (e.g. "#123") - resolving
 *                   it to an actual Task/sourceRef is Chain's job, not
 *                   Bridge's; this lane doesn't know the target exists.
 * @param relation   how targetRef relates to the source item.
 * @param confidence 0.0..1.0, 0 for "found nothing" - never null.
 * @param rationale  the phrase the extraction came from, e.g. "might block
 *                   the release of #789 but I'm not sure" - ambiguous
 *                   phrasing like that is exactly what confidence is for,
 *                   not a reason to invent a fifth relation type.
 */
public record DependencySuggestion(String targetRef, DependencyRelation relation, double confidence,
                                    String rationale) {
}
