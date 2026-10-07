package com.rnd.chain.events;

import com.google.protobuf.Timestamp;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Field-level parsing shared by the inbound Protobuf event handlers. */
final class EventFields {

    private EventFields() {
    }

    /** Throws NonRetryableEventException, since a malformed id never becomes valid on retry. */
    static UUID parseUuid(String raw, String fieldName) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new NonRetryableEventException("Invalid " + fieldName + " (not a UUID): " + raw, e);
        }
    }

    static LocalDateTime toLocalDateTime(Timestamp ts) {
        return LocalDateTime.ofEpochSecond(ts.getSeconds(), ts.getNanos(), ZoneOffset.UTC);
    }
}
