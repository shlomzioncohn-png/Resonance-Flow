package com.rnd.bridge.core;

import java.time.Instant;

/**
 * The incremental-sync position for a lane. {@code since} backs Slack's timestamp-based
 * "oldest" cursor; {@code syncToken} backs Google Calendar's syncToken-based incremental
 * sync (a page token that also surfaces updates/deletes, not just creations - see
 * RealCalendarSourceReader). A lane only ever populates the one its source actually uses.
 */
public record ReadCursor(Instant since, String syncToken, String historyId) {

    /** Compatibility constructor for callers with no syncToken/historyId to carry. */
    public ReadCursor(Instant since) {
        this(since, null, null);
    }

    /** Compatibility constructor for Calendar's syncToken-only callers. */
    public ReadCursor(Instant since, String syncToken) {
        this(since, syncToken, null);
    }

    public static ReadCursor full() {
        return new ReadCursor(null, null, null);
    }
}
