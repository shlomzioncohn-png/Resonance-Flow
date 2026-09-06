package com.rnd.bridge.intake.parser.calendar;

import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.intake.model.SourceRecord;
import java.util.List;

/**
 * Reads Google Calendar data into SourceRecords. Selected implementation (Fixture vs. Real)
 * is chosen by bridge.intake.calendar.mode — see plan.md.
 * cursor.syncToken() is the incremental-sync position (null means "read everything"). Only
 * RealCalendarSourceReader honors it today (via events.list's syncToken parameter);
 * FixtureCalendarSourceReader ignores it, fixtures don't need incremental sync.
 */
public interface CalendarSourceReader {

    List<SourceRecord> read(ReadCursor cursor);
}
