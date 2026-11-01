package com.rnd.chain.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What a flag's {@code entityIds} refer to: task ids for {@code TASK}, {@code "taskId>dependsOnTaskId"}
 * for {@code EDGE}/{@code EDGE_PAIR}, and the tasks of a chain in order for {@code CHAIN}.
 */
public enum FlowHealthEntityType {
    TASK, EDGE, EDGE_PAIR, CHAIN;

    @JsonValue
    public String value() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static FlowHealthEntityType fromValue(String value) {
        return valueOf(value.toUpperCase());
    }
}
