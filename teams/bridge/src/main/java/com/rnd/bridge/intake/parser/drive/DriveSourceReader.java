package com.rnd.bridge.intake.parser.drive;

import com.rnd.bridge.intake.model.SourceRecord;
import java.util.List;

/**
 * Reads Google Drive data into SourceRecords. Selected implementation (Fixture vs. Real) is
 * chosen by bridge.intake.drive.mode — see plan.md.
 */
public interface DriveSourceReader {

    List<SourceRecord> read();
}
