package com.rnd.bridge.raw.model;

/**
 * What produced a {@link Classification}: a deterministic rule (cheap, checked first - e.g. a
 * near-silent recording or an all-white page) or the model, for whatever the rules couldn't
 * settle. Lets a consumer weigh the decision accordingly - a RULE outcome is reproducible, a
 * MODEL outcome is a judgement.
 */
public enum DecidedBy {
    MODEL,
    RULE
}
