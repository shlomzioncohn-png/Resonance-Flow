package com.rnd.chain.dto;

/** A history entry with its DB {@code sequence}, which the tenant-level cursor is built from. */
public record TaskStateHistoryRow(TaskStateHistoryEntry entry, long sequence) {
}
