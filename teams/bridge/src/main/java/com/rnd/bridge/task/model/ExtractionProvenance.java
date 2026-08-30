package com.rnd.bridge.task.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ExtractionProvenance {
    ETL,
    ML_INFERRED,
    MANUAL;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
