package com.rnd.bridge.raw.model;

import java.util.Objects;

/**
 * One classified value plus how confident its producer is in it - {@link Classification} gives
 * every label its own confidence, since a model can be sure an item is an invoice and unsure
 * whether it's sensitive. confidence is in [0, 1]; a model's self-reported confidence is not a
 * calibrated probability, so only threshold it after measuring against labelled data.
 */
public record Label<T>(T value, double confidence) {

    private static final double RULE_CONFIDENCE = 1.0;

    public Label {
        Objects.requireNonNull(value, "value");
        if (!(confidence >= 0.0 && confidence <= 1.0)) {
            throw new IllegalArgumentException("confidence must be in [0, 1], was " + confidence);
        }
    }

    /** A label set by a deterministic rule - always full confidence. */
    public static <T> Label<T> fromRule(T value) {
        return new Label<>(value, RULE_CONFIDENCE);
    }
}
