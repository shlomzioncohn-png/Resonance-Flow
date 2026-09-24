package com.rnd.chain.dto;

/**
 * Who/what triggered a dependency change. {@code MIND} and {@code SYSTEM}
 * are automated, event-driven sources exposed to retry / at-least-once
 * delivery, so {@code sourceRef} is required for them; {@code USER} is a
 * direct synchronous call, so {@code sourceRef} is optional for it.
 */
public enum DependencyChangeSource {
    MIND,
    USER,
    SYSTEM
}
