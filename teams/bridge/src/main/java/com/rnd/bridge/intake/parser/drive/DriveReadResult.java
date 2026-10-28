package com.rnd.bridge.intake.parser.drive;

import com.rnd.bridge.intake.model.SourceRecord;
import java.util.List;

/**
 * The outcome of exactly one {@link DriveSourceReader#read} call: the records it produced, and
 * nextToken - the incremental-sync cursor (Drive's changes.list startPageToken) produced by
 * that SAME read. Carrying both together, as one per-call return value, is what makes the
 * token safe under concurrent calls - see CalendarReadResult's javadoc for the full reasoning
 * this mirrors.
 * nextToken is null when this read produced no new cursor to advance to -
 * FixtureDriveSourceReader never supports incremental sync, so it always returns null here.
 */
public record DriveReadResult(List<SourceRecord> records, String nextToken) {
}
