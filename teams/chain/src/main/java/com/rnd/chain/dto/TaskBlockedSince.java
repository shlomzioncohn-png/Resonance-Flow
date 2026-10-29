package com.rnd.chain.dto;

import java.time.Instant;
import java.util.UUID;

/** A currently blocked task and the time of its latest BLOCKED event. */
public record TaskBlockedSince(UUID taskId, Instant blockedSince) {
}
