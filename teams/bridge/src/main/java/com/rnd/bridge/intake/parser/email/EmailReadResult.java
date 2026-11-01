package com.rnd.bridge.intake.parser.email;

import com.rnd.bridge.intake.model.SourceRecord;
import java.util.List;

/**
 * The outcome of exactly one {@link EmailSourceReader#read} call: the records it produced, and
 * nextHistoryId - the incremental-sync cursor Gmail returned for that SAME call (see
 * RealEmailSourceReader). Carrying both together, as one per-call return value, mirrors
 * CalendarReadResult's reasoning: the token belongs to whichever read produced it, not to shared
 * reader state a second concurrent call could overwrite before the first caller reads it back
 * (see UnstructuredIntakeService, which persists nextHistoryId only after this same call's
 * records have all been processed/published successfully).
 * nextHistoryId is null when this read produced no new cursor to advance to -
 * FixtureEmailSourceReader never supports incremental sync, so it always returns null here.
 */
public record EmailReadResult(List<SourceRecord> records, String nextHistoryId) {
}
