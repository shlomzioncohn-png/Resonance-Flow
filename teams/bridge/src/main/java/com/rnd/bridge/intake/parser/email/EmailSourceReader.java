package com.rnd.bridge.intake.parser.email;

import com.rnd.bridge.intake.model.SourceRecord;
import java.util.List;

/**
 * Reads Gmail data into SourceRecords. Selected implementation (Fixture vs. Real) is chosen
 * by bridge.intake.email.mode — see plan.md.
 */
public interface EmailSourceReader {

    List<SourceRecord> read();
}
