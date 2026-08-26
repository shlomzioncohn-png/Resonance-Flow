package com.rnd.chain.events;

import com.rnd.chain.dto.ProcessedEvent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary in-memory implementation of ProcessedEventStore.
 *
 * TODO: this does NOT survive a service restart or run across multiple
 * instances of the service - all processed-event history is lost on
 * restart, and each instance has its own separate memory. This is only
 * safe for local development/testing. Replace with a persistent
 * implementation (DB table, Redis, etc.) once the technology is decided,
 * before this goes anywhere near production.
 */
@Component
public class InMemoryProcessedEventStore implements ProcessedEventStore {

    private final Map<UUID, ProcessedEvent> processedEvents = new ConcurrentHashMap<>();

    @Override
    public boolean isProcessed(UUID eventId) {
        return processedEvents.containsKey(eventId);
    }

    @Override
    public void markProcessed(ProcessedEvent event) {
        processedEvents.put(event.getEventId(), event);
    }
}