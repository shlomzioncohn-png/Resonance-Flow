package com.rnd.bridge.enrichment;

import java.util.List;
import java.util.Objects;

/**
 * Everything {@link EnrichmentService} extracted from one piece of free
 * text, keyed back to the {@code Task} it enriches by {@code sourceRef}
 * (same value as {@code Task.sourceRef} - see {@code AbstractTaskMapper}).
 * <p>
 * Every field here is a suggestion, never a fact: nothing in this record
 * is written into {@code Task} by this lane. A field found nothing rather
 * than "wasn't asked" - {@code dueDate}/{@code severity} null,
 * {@code dependencies} empty - never a thrown exception; see
 * {@link EnrichmentService} for why that distinction matters.
 *
 * @param sourceRef   the enriched item's {@code Task.sourceRef}.
 * @param dueDate     null when no due date was found in the text.
 * @param severity    null when no severity signal was found.
 * @param dependencies empty (never null) when none were found.
 */
public record TaskEnrichment(
        String sourceRef,
        DueDateSuggestion dueDate,
        SeveritySuggestion severity,
        List<DependencySuggestion> dependencies) {

    public TaskEnrichment {
        Objects.requireNonNull(sourceRef, "sourceRef must not be null");
        dependencies = List.copyOf(dependencies);
    }

    public static TaskEnrichment empty(String sourceRef) {
        return new TaskEnrichment(sourceRef, null, null, List.of());
    }
}
