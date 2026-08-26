package com.rnd.bridge.core;

import com.rnd.bridge.core.error.ErrorPolicy;
import com.rnd.bridge.core.error.LaneException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Template Method for an intake lane: read, map, validate, publish - one
 * record at a time - with {@link #errorPolicy()} deciding whether a failed
 * record aborts the run or is recorded and skipped so the rest of the batch
 * still gets processed.
 */

/**
 * ...
 *
 * @param <S> the raw source record type - a GitHub issue, a CRM deal
 * @param <O> the payload published downstream - Task or RawObjectEvent
 */
public abstract class AbstractIntakeLane<S, O extends BridgePayload> {

    protected abstract SourceReader<S> sourceReader();

    protected abstract PayloadMapper<S, O> payloadMapper();

    protected abstract PayloadValidator<O> payloadValidator();

    protected abstract PayloadPublisher<O> payloadPublisher();

    protected ErrorPolicy errorPolicy() {
        return ErrorPolicy.RECORD_AND_CONTINUE;
    }

    public final IntakeResult run(ReadCursor cursor, Instant asOf) {
        int read = 0;
        int published = 0;
        int rejected = 0;
        List<LaneException> errors = new ArrayList<>();

        try (Stream<S> sources = sourceReader().read(cursor)) {
            for (S source : (Iterable<S>) sources::iterator) {
                read++;
                try {
                    O payload = payloadMapper().map(source, asOf);
                    payloadValidator().validate(payload);
                    payloadPublisher().publish(payload);
                    published++;
                } catch (LaneException e) {
                    rejected++;
                    errors.add(e);
                    if (errorPolicy().shouldAbort(e)) {
                        break;
                    }
                }
            }
        }

        return new IntakeResult(read, published, rejected, errors);
    }
}
