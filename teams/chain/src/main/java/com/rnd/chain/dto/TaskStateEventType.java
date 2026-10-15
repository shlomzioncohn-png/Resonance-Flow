package com.rnd.chain.dto;

/** What happened to a task in a {@link TaskStateHistoryEntry}. */
public enum TaskStateEventType {
    /** The task's status changed (from/to status are set). */
    STATUS_CHANGED,
    /** blocking_count went from 0 to a positive value. */
    BLOCKED,
    /** blocking_count returned to 0. */
    UNBLOCKED
}
