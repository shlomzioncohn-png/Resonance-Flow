package com.rnd.bridge.intake.parser.calendar;

import com.rnd.bridge.intake.model.SourceRecord;
import java.util.List;

/**
 * The outcome of exactly one {@link CalendarSourceReader#read} call: the records it produced,
 * and nextSyncToken - the incremental-sync cursor Google returned from the final page of that
 * SAME sync (see RealCalendarSourceReader). Carrying both together, as one per-call return
 * value, is what makes the token safe under concurrent calls: it belongs to whichever read
 * produced it, not to shared reader state that a second concurrent call could overwrite before
 * the first caller reads it back (see UnstructuredIntakeController, which persists
 * nextSyncToken only after this same call's records have all been processed/published
 * successfully).
 * nextSyncToken is null when this read produced no new cursor to advance to -
 * FixtureCalendarSourceReader never supports incremental sync, so it always returns null here.
 */
public record CalendarReadResult(List<SourceRecord> records, String nextSyncToken) {
}
