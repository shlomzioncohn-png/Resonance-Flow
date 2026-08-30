package com.rnd.chain.events;

import com.rnd.chain.dto.ProcessedEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary in-memory implementation of ProcessedEventStore.
 *
 * TODO: this does NOT survive a service restart or run across multiple
 * instances of the service - all claim history is lost on restart, and
 * each instance has its own separate memory. This is only safe for local
 * development/testing. Replace with a persistent implementation (DB
 * table, Redis, etc.) once the technology is decided, before this goes
 * anywhere near production.
 *
 * Guarded by chain.events.processed-store so it cannot silently become the
 * active store in an environment that didn't explicitly ask for it: the
 * default ("in-memory") matches this implementation's name, but a future
 * persistent implementation can take over by registering under a
 * different value for the same property and configuring that value
 * instead - or a production profile can set this property to a value
 * nothing implements, which fails application startup fast instead of
 * silently running with an unsafe store.
 */
@Component
@ConditionalOnProperty(prefix = "chain.events", name = "processed-store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryProcessedEventStore implements ProcessedEventStore {

    private final Map<UUID, ProcessedEvent> claims = new ConcurrentHashMap<>();

    @Override
    public boolean tryClaim(ProcessedEvent event) {
        // putIfAbsent is atomic: exactly one concurrent caller for a given
        // eventId gets back null (this call installed the entry, i.e. won
        // the claim) and every other caller - concurrent or later - gets
        // back the already-stored entry. That is the actual fix: the
        // previous isProcessed()-then-markProcessed() shape was two
        // separate operations with a window between them; this is one.
        return claims.putIfAbsent(event.eventId(), event) == null;
    }

    @Override
    public void release(UUID eventId) {
        claims.remove(eventId);
    }
}
