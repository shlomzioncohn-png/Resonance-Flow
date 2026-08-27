package com.rnd.bridge.core;

import java.time.Instant;

/**
 * The incremental-sync position for a lane. Only full sync exists today;
 * {@code since} is here so a lane can add incremental reads later without a
 * new cursor type.
 */
public record ReadCursor(Instant since) {

    public static ReadCursor full() {
        return new ReadCursor(null);
    }
}
