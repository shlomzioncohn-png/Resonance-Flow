package com.rnd.inbox.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventTest {

    @Test
    void newEvent_generatesUniqueEventIdPerCall() {
        Event<String> first = Event.newEvent(EventType.TASK_CREATED, "inbox", null, "payload");
        Event<String> second = Event.newEvent(EventType.TASK_CREATED, "inbox", null, "payload");

        assertThat(first.getEventId()).isNotNull();
        assertThat(second.getEventId()).isNotNull();
        assertThat(first.getEventId()).isNotEqualTo(second.getEventId());
    }

    @Test
    void newEvent_setsTimestampToNow() {
        Instant before = Instant.now();
        Event<String> event = Event.newEvent(EventType.TASK_CREATED, "inbox", null, "payload");
        Instant after = Instant.now();

        assertThat(event.getTimestamp()).isNotNull();
        assertThat(event.getTimestamp()).isBetween(before, after);
    }

    @Test
    void newEvent_acceptsNullCorrelationId() {
        Event<String> event = Event.newEvent(EventType.TASK_CREATED, "inbox", null, "payload");

        assertThat(event.getCorrelationId()).isNull();
    }

    @Test
    void newEvent_retainsCorrelationIdWhenProvided() {
        UUID correlationId = UUID.randomUUID();
        Event<String> event = Event.newEvent(EventType.TASK_CREATED, "inbox", correlationId, "payload");

        assertThat(event.getCorrelationId()).isEqualTo(correlationId);
    }

    @Test
    void newEvent_retainsEventTypeSourceServiceAndPayload() {
        Event<String> event = Event.newEvent(EventType.TASK_UPDATED, "inbox", null, "payload");

        assertThat(event.getEventType()).isEqualTo(EventType.TASK_UPDATED);
        assertThat(event.getSourceService()).isEqualTo("inbox");
        assertThat(event.getPayload()).isEqualTo("payload");
    }
}
