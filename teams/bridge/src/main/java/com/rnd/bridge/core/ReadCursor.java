package com.rnd.bridge.core;

import java.time.Instant;

/**
 * The incremental-sync position for a lane. {@code since} backs Slack's timestamp-based
 * "oldest" cursor; {@code token} is a single generic slot for every other source's opaque
 * incremental-position marker (Calendar's syncToken, Gmail's historyId, Drive's
 * startPageToken) - they all play the exact same structural role (round-tripped to the one
 * API that minted it, never two needed at once for a given source), so one field is used
 * instead of growing a dedicated one per source forever. A lane only ever populates the
 * field(s) its source actually uses.
 */
public record ReadCursor(Instant since, String token) {

    /** Compatibility constructor for callers with no token to carry. */
    public ReadCursor(Instant since) {
        this(since, null);
    }

    public static ReadCursor full() {
        return new ReadCursor(null, null);
    }
}
