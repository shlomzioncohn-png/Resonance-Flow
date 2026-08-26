package com.rnd.chain.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Marks that a given event ID has already been processed by this service,
 * so that receiving the same event more than once does not create
 * duplicate state changes.
 *
 * This is a plain class on purpose - the project has not yet decided
 * which storage technology will back this (DB table, Redis, etc.).
 * Once that is decided, this class stays as-is; only the storage
 * implementation (see ProcessedEventStore) needs to change.
 */
public class ProcessedEvent {

    private final UUID eventId;
    private final String eventType;
    private final Instant processedAt;

    public ProcessedEvent(UUID eventId, String eventType, Instant processedAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = processedAt;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}


