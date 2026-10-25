package com.rnd.inbox.workinsights;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum EffortBucket {
    SHORT,
    MEDIUM,
    LONG;

    public static EffortBucket fromMinutes(int minutes, int shortMaxMinutes, int mediumMaxMinutes) {
        if (minutes <= shortMaxMinutes) {
            return SHORT;
        }
        if (minutes <= mediumMaxMinutes) {
            return MEDIUM;
        }
        return LONG;
    }

    @JsonValue
    public String jsonName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
