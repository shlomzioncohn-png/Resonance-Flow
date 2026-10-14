package com.rnd.bridge.intake.parser.calendar;

import com.rnd.bridge.core.ReadCursor;

/**
 * Reads Google Calendar data into SourceRecords. Selected implementation (Fixture vs. Real)
 * is chosen by bridge.intake.calendar.mode — see plan.md.
 * cursor.syncToken() is the incremental-sync position (null means "read everything"). Only
 * RealCalendarSourceReader honors it today (via events.list's syncToken parameter);
 * FixtureCalendarSourceReader ignores it, fixtures don't need incremental sync.
 * Returns a {@link CalendarReadResult} rather than a plain List<SourceRecord> so the
 * incremental-sync cursor produced by a given read is tied to that exact call, not to shared
 * reader state - see CalendarReadResult's javadoc for why that matters under concurrent calls.
 */
public interface CalendarSourceReader {

    CalendarReadResult read(ReadCursor cursor);
}
