package com.rnd.chain.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Severity of a flag; stored and serialized lower-case, as the job writes it. */
public enum FlowHealthSeverity {
    LOW, MEDIUM, HIGH;

    @JsonValue
    public String value() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static FlowHealthSeverity fromValue(String value) {
        return valueOf(value.toUpperCase());
    }
}
