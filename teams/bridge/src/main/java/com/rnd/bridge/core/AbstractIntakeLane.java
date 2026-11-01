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
 *
 * @param <S> the raw source record type - a GitHub issue, a CRM deal
 * @param <O> the payload published downstream - Task or RawObjectEvent
 */
public abstract class AbstractIntakeLane<S, O extends BridgePayload> {

    protected abstract SourceReader<S> sourceReader();

    protected abstract PayloadMapper<S, O> payloadMapper();

    protected abstract PayloadValidator<O> payloadValidator();

    protected abstract PayloadPublisher<O> payloadPublisher();

    /**
     * Optional side-effect hook between mapping and validation - e.g. persisting raw bytes
     * somewhere durable before the event is published. No-op by default: most lanes map
     * straight to the payload that gets published, with no separate durable copy to write.
     * Override only if the lane actually needs it (see UnstructuredIntakeLane).
     */
    protected void afterMap(S source, O payload, Instant asOf) {
    }

    /**
     * Optional side-effect hook after a successful publish - e.g. confirming durable state
     * that was only tentatively persisted before the payload was known to have actually
     * reached the downstream broker (see UnstructuredIntakeLane's chat-group publish
     * confirmation). No-op by default. Only reached when payloadPublisher().publish(payload)
     * returned without throwing - if publish() throws, run()'s catch block below skips this
     * exactly like a mapping/landing failure would, so nothing here can be mistaken for a
     * confirmed publish that never actually happened.
     * run() only increments published AFTER this returns successfully (see run()), so a source
     * whose afterPublish throws a LaneException is counted as rejected, not as both published
     * and rejected - implementations that can fail here (anything doing its own I/O, e.g.
     * UnstructuredIntakeLane's GroupStateStore confirmation) must wrap failures in LaneException
     * themselves, the same way afterMap does, so errorPolicy() gets a chance to record and
     * continue instead of an unrelated RuntimeException escaping run() and aborting the batch.
     */
    protected void afterPublish(S source, O payload, Instant asOf) {
    }

    protected ErrorPolicy errorPolicy() {
        return ErrorPolicy.RECORD_AND_CONTINUE;
    }

    public final IntakeResult run(ReadCursor cursor, Instant asOf) {
        try (Stream<S> sources = sourceReader().read(cursor)) {
            return run(sources, asOf);
        }
    }

    /**
     * Same read/map/validate/publish loop as {@link #run(ReadCursor, Instant)}, but against an
     * already-obtained Stream instead of one this method fetches itself via sourceReader().read.
     * Exists for callers that must read from the source themselves outside the generic
     * SourceReader/ReadCursor contract - e.g. UnstructuredIntakeService's Calendar path,
     * which needs the incremental-sync token tied to its own single read() call (see
     * CalendarReadResult) rather than the token being silently dropped inside a reader this
     * method would otherwise call on the caller's behalf.
     */
    public final IntakeResult run(Stream<S> sources, Instant asOf) {
        int read = 0;
        int published = 0;
        int rejected = 0;
        List<LaneException> errors = new ArrayList<>();

        for (S source : (Iterable<S>) sources::iterator) {
            read++;
            try {
                O payload = payloadMapper().map(source, asOf);
                afterMap(source, payload, asOf);
                payloadValidator().validate(payload);
                payloadPublisher().publish(payload);
                afterPublish(source, payload, asOf);
                published++;
            } catch (LaneException e) {
                rejected++;
                errors.add(e);
                if (errorPolicy().shouldAbort(e)) {
                    break;
                }
            }
        }

        return new IntakeResult(read, published, rejected, errors);
    }
}
