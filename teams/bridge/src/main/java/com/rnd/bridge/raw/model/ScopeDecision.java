package com.rnd.bridge.raw.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ScopeDecision {
    @JsonProperty("included") INCLUDED,
    @JsonProperty("excluded") EXCLUDED
}
