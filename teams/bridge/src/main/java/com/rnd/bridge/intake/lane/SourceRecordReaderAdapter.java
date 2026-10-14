package com.rnd.bridge.intake.lane;

import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.core.SourceReader;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.parser.SourceRecordLoader;
import com.rnd.bridge.raw.model.RawSourceType;
import java.util.stream.Stream;

/**
 * Binds {@link SourceRecordLoader} (which dispatches across all six {@link RawSourceType}s)
 * to a single fixed source type, so one {@link UnstructuredIntakeLane} instance reads exactly
 * one source — matching how {@code AbstractIntakeLane} expects a lane to be bound to one
 * external source. The cursor is forwarded as-is to {@link SourceRecordLoader#load}; whether
 * it's actually honored depends on the source (only Slack does today - see plan.md "Cursor
 * persistence").
 */
final class SourceRecordReaderAdapter implements SourceReader<SourceRecord> {

    private final RawSourceType sourceType;
    private final SourceRecordLoader loader;

    SourceRecordReaderAdapter(RawSourceType sourceType, SourceRecordLoader loader) {
        this.sourceType = sourceType;
        this.loader = loader;
    }

    @Override
    public Stream<SourceRecord> read(ReadCursor cursor) {
        return loader.load(sourceType, cursor).stream();
    }
}
