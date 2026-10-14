package com.rnd.bridge.core;

import java.time.Instant;

/**
 * The incremental-sync position for a lane. {@code since} backs Slack's timestamp-based
 * "oldest" cursor; {@code syncToken} backs Google Calendar's syncToken-based incremental
 * sync (a page token that also surfaces updates/deletes, not just creations - see
 * RealCalendarSourceReader). A lane only ever populates the one its source actually uses.
 */
public record ReadCursor(Instant since, String syncToken) {

    /** Compatibility constructor for callers with no syncToken to carry. */
    public ReadCursor(Instant since) {
        this(since, null);
    }

    public static ReadCursor full() {
        return new ReadCursor(null, null);
    }
}
