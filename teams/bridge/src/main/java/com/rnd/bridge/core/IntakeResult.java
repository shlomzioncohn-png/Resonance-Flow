package com.rnd.bridge.core;

import com.rnd.bridge.core.error.LaneException;

import java.util.List;

public record IntakeResult(int read, int published, int rejected, List<LaneException> errors) {

    public IntakeResult {
        errors = List.copyOf(errors);
    }
}
