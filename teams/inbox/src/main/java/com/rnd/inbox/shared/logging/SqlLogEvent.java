package com.rnd.inbox.shared.logging;

/** What a SQL log line reports; written as the line's "event" field. */
public enum SqlLogEvent {
    QUERY,
    SLOW_QUERY,
    QUERY_FAILED
}
