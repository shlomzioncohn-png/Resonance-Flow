package com.rnd.bridge.task.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum RoleContext {
    DEVELOPER,
    MANAGER,
    HIGHER_MANAGER,
    FIELD_ENGINEER,
    SALESPERSON,
    CEO;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
