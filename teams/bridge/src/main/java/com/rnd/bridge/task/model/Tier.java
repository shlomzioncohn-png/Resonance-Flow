package com.rnd.bridge.task.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Tier {
    OPERATIONAL,
    TACTICAL,
    STRATEGIC;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
