package com.rnd.chain.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EventEnvelopeTest {

    @Test
    void constructor_generatesEventIdAndOccurredAt_whenNotProvided() {
        EventEnvelope<String> event = new EventEnvelope<>("task.scheduling.updated", "corr-1", "payload");

        assertNotNull(event.getEventId());
        assertNotNull(event.getOccurredAt());
        assertEquals("task.scheduling.updated", event.getEventType());
        assertEquals("corr-1", event.getCorrelationId());
        assertEquals("payload", event.getPayload());
    }

    @Test
    void constructor_throwsException_whenEventTypeIsNull() {
        assertThrows(NullPointerException.class,
                () -> new EventEnvelope<>(null, "corr-1", "payload"));
    }

    @Test
    void constructor_throwsException_whenPayloadIsNull() {
        assertThrows(NullPointerException.class,
                () -> new EventEnvelope<>("task.scheduling.updated", "corr-1", null));
    }

    @Test
    void constructor_allowsNullCorrelationId() {
        EventEnvelope<String> event = new EventEnvelope<>("task.scheduling.updated", null, "payload");

        assertNull(event.getCorrelationId());
    }

    @Test
    void equals_returnsTrue_whenSameEventId() {
        UUID id = UUID.randomUUID();
        EventEnvelope<String> event1 = new EventEnvelope<>(id, "type", Instant.now(), "corr", "payload-a");
        EventEnvelope<String> event2 = new EventEnvelope<>(id, "type", Instant.now(), "corr", "payload-b");

        assertEquals(event1, event2);
        assertEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    void equals_returnsFalse_whenDifferentEventId() {
        EventEnvelope<String> event1 = new EventEnvelope<>("type", "corr", "payload");
        EventEnvelope<String> event2 = new EventEnvelope<>("type", "corr", "payload");

        assertNotEquals(event1, event2);
    }
}