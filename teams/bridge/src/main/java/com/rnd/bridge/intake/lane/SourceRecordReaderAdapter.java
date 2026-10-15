package com.rnd.bridge.intake.lane;

import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.core.SourceReader;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.parser.SourceRecordGrouper;
import com.rnd.bridge.intake.parser.SourceRecordLoader;
import com.rnd.bridge.raw.model.RawSourceType;
import java.util.List;
import java.util.stream.Stream;

/**
 * Binds {@link SourceRecordLoader} (which dispatches across all six {@link RawSourceType}s)
 * to a single fixed source type, so one {@link UnstructuredIntakeLane} instance reads exactly
 * one source — matching how {@code AbstractIntakeLane} expects a lane to be bound to one
 * external source. The cursor is forwarded as-is to {@link SourceRecordLoader#load}; whether
 * it's actually honored depends on the source (only Slack does today - see plan.md "Cursor
 * persistence"). The loader's flat list is grouped by correlationId (see
 * {@link SourceRecordGrouper}) before being handed to the lane, since the lane's unit of work is
 * now one logical item's complete object group, not a single SourceRecord.
 */
final class SourceRecordReaderAdapter implements SourceReader<List<SourceRecord>> {

    private final RawSourceType sourceType;
    private final SourceRecordLoader loader;

    SourceRecordReaderAdapter(RawSourceType sourceType, SourceRecordLoader loader) {
        this.sourceType = sourceType;
        this.loader = loader;
    }

    @Override
    public Stream<List<SourceRecord>> read(ReadCursor cursor) {
        return SourceRecordGrouper.groupByCorrelationId(loader.load(sourceType, cursor)).stream();
    }
}
