package com.rnd.chain.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Generic envelope for every event that Chain publishes or consumes.
 *
 * TODO: this is a temporary placeholder. Depending on the project-wide
 * conventions (likely defined by the Relay team), the real structure may
 * differ - additional fields, different field names, or a different
 * serialization format (e.g. Avro instead of JSON). Confirm this with the
 * team and update this class accordingly once the final structure is known.
 *
 * @param <T> the type of the event-specific payload (e.g. TaskSchedulingUpdatedPayload)
 */
public class EventEnvelope<T> {

    private final UUID eventId;
    private final String eventType;
    private final Instant occurredAt;
    private final String correlationId;
    private final T payload;

    public EventEnvelope(String eventType, String correlationId, T payload) {
        this(UUID.randomUUID(), eventType, Instant.now(), correlationId, payload);
    }

    public EventEnvelope(UUID eventId, String eventType, Instant occurredAt,
                         String correlationId, T payload) {
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        this.correlationId = correlationId;
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public T getPayload() {
        return payload;
    }

    // Identity, not content equality: two envelopes are "the same event" iff they
    // share an eventId, regardless of payload.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventEnvelope<?> that)) return false;
        return eventId.equals(that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId);
    }

    @Override
    public String toString() {
        return "EventEnvelope{" +
                "eventId=" + eventId +
                ", eventType='" + eventType + '\'' +
                ", occurredAt=" + occurredAt +
                ", correlationId='" + correlationId + '\'' +
                '}';
    }
}