package com.rnd.bridge.raw.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Objects;

/**
 * Which classifier produced a {@link Classification}, so results stay attributable after a
 * provider or prompt change - model is e.g. "gemini-2.5-flash", or {@link #RULES_MODEL} when
 * {@link DecidedBy#RULE}; promptVersion is bumped on every prompt change.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ClassifierInfo(String model, String promptVersion) {

    public static final String RULES_MODEL = "rules";

    public ClassifierInfo {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(promptVersion, "promptVersion");
    }
}
