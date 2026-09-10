package com.rnd.bridge.intake.parser.calendar;

import com.rnd.bridge.intake.model.SourceRecord;
import java.util.List;

/**
 * Reads Google Calendar data into SourceRecords. Selected implementation (Fixture vs. Real)
 * is chosen by bridge.intake.calendar.mode — see plan.md.
 */
public interface CalendarSourceReader {

    List<SourceRecord> read();
}
