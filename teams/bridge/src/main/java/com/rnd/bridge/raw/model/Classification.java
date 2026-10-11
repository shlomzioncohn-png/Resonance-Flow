package com.rnd.bridge.raw.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Objects;

/**
 * Bridge's whole-item triage of a {@link RawObjectEvent}: what decided it, what kind of item it
 * is, how usable the file is, and how sensitive the content is - the basis its scopeDecision
 * was set from. Describes the logical item as a whole, never what's inside it: no tasks,
 * people, dates or priority - extraction stays with Mind and priority with Chain, and reason
 * is held to the same rule.
 * <p>
 * Optional on the event: absent means nothing classified the item (disabled, or the classifier
 * failed), so scopeDecision is the INCLUDED default rather than a judgement. A low-confidence
 * result is still attached - it just never makes the item EXCLUDED. Wire contract:
 * shared/proto/raw-object-event.proto, where consumers are told to treat an unknown value as
 * "other" so new values stay non-breaking.
 *
 * @param reason human-readable rationale for debugging and review only - not to be parsed or
 *               branched on
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Classification(
        DecidedBy decidedBy,
        Label<ItemCategory> category,
        Label<Quality> quality,
        Label<Sensitivity> sensitivity,
        String reason,
        ClassifierInfo classifier) {

    public Classification {
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(sensitivity, "sensitivity");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(classifier, "classifier");
    }
}
