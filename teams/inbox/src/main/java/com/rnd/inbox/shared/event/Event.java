package com.rnd.inbox.shared.event;

import java.time.Instant;
import java.util.UUID;

public final class Event<T> {

    private final UUID eventId;
    private final EventType eventType;
    private final Instant timestamp;
    private final String sourceService;
    private final UUID correlationId;
    private final T payload;

    private Event(UUID eventId, EventType eventType, Instant timestamp, String sourceService,
                  UUID correlationId, T payload) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.timestamp = timestamp;
        this.sourceService = sourceService;
        this.correlationId = correlationId;
        this.payload = payload;
    }

    public static <T> Event<T> newEvent(EventType eventType, String sourceService, UUID correlationId, T payload) {
        return new Event<>(UUID.randomUUID(), eventType, Instant.now(), sourceService, correlationId, payload);
    }

    public static <T> Event<T> existing(UUID eventId, EventType eventType, Instant timestamp,
                                        String sourceService, UUID correlationId, T payload) {
        return new Event<>(eventId, eventType, timestamp, sourceService, correlationId, payload);
    }

    public UUID getEventId() {
        return eventId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getSourceService() {
        return sourceService;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public T getPayload() {
        return payload;
    }
}
