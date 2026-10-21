package com.rnd.bridge.media.classification;

import com.rnd.bridge.raw.model.Classification;
import com.rnd.bridge.raw.model.ScopeDecision;
import java.util.Objects;

/**
 * The outcome of classifying one media item: the scope decision its event and MinIO tags carry,
 * and the classification it was derived from - null when nothing classified the item, in which
 * case scopeDecision is always INCLUDED.
 */
public record Triage(ScopeDecision scopeDecision, Classification classification) {

    public static final Triage UNCLASSIFIED = new Triage(ScopeDecision.INCLUDED, null);

    public Triage {
        Objects.requireNonNull(scopeDecision, "scopeDecision");
        if (classification == null && scopeDecision != ScopeDecision.INCLUDED) {
            throw new IllegalArgumentException("Only a classified item can be " + scopeDecision);
        }
    }
}
