package com.rnd.bridge.core;

import com.rnd.bridge.core.error.LaneException;
import com.rnd.bridge.core.error.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A validator throwing {@link ValidationException} must be caught by
 * {@link AbstractIntakeLane#run} and routed through the error policy - not
 * propagate and abort the whole run. This pins that wiring so a future
 * change to the exception hierarchy or the catch clause fails loudly here
 * instead of silently in production.
 */
class AbstractIntakeLaneTest {

    private record StubPayload(String tenantId, String dedupKey) implements BridgePayload {
    }

    @Test
    void aValidationExceptionFromTheValidatorIsCaughtAndRecordedNotThrown() {
        AbstractIntakeLane<String, StubPayload> lane = new AbstractIntakeLane<>() {
            @Override
            protected SourceReader<String> sourceReader() {
                return cursor -> Stream.of("only-record");
            }

            @Override
            protected PayloadMapper<String, StubPayload> payloadMapper() {
                return (source, asOf) -> new StubPayload("tenant", "dedup:" + source);
            }

            @Override
            protected PayloadValidator<StubPayload> payloadValidator() {
                return payload -> {
                    throw new ValidationException("always invalid for this test");
                };
            }

            @Override
            protected PayloadPublisher<StubPayload> payloadPublisher() {
                return payload -> {
                    throw new AssertionError("must not publish a rejected record");
                };
            }
        };

        IntakeResult result = lane.run(ReadCursor.full(), Instant.parse("2026-08-26T00:00:00Z"));

        assertEquals(1, result.read());
        assertEquals(0, result.published());
        assertEquals(1, result.rejected());

        List<LaneException> errors = result.errors();
        assertEquals(1, errors.size());
        assertInstanceOf(ValidationException.class, errors.get(0));
    }
}
