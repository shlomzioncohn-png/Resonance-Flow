package com.rnd.bridge.intake.parser.drive;

import com.rnd.bridge.core.ReadCursor;

/**
 * Reads Google Drive data into SourceRecords. Selected implementation (Fixture vs. Real) is
 * chosen by bridge.intake.drive.mode — see plan.md.
 * cursor.token() carries changes.list's startPageToken (null means "do a full files.list
 * baseline scan" - see RealDriveSourceReader) - same per-call-result shape as
 * CalendarSourceReader.read(ReadCursor), for the same concurrency reasoning (see
 * CalendarReadResult's javadoc).
 */
public interface DriveSourceReader {

    DriveReadResult read(ReadCursor cursor);
}
