package com.rnd.bridge.intake.parser.email;

import com.rnd.bridge.core.ReadCursor;

/**
 * Reads Gmail data into SourceRecords. Selected implementation (Fixture vs. Real) is chosen
 * by bridge.intake.email.mode — see plan.md.
 * cursor.historyId() is the incremental-sync position (null means "read everything"). Only
 * RealEmailSourceReader honors it today (via users.history.list's startHistoryId parameter);
 * FixtureEmailSourceReader ignores it, fixtures don't need incremental sync.
 * Returns an {@link EmailReadResult} rather than a plain List<SourceRecord> so the
 * incremental-sync cursor produced by a given read is tied to that exact call, not to shared
 * reader state - see EmailReadResult's javadoc for why that matters under concurrent calls.
 */
public interface EmailSourceReader {

    EmailReadResult read(ReadCursor cursor);
}
