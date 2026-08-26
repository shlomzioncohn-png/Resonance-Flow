
package com.rnd.chain.events;

import com.rnd.chain.dto.ProcessedEvent;

import java.util.UUID;

/**
 * Tracks which event IDs have already been processed.
 *
 * The Consumer depends only on this interface, not on how it is stored -
 * so the actual storage (DB table, Redis, etc.) can be decided and
 * plugged in later without changing the Consumer at all.
 */
public interface ProcessedEventStore {

    boolean isProcessed(UUID eventId);

    void markProcessed(ProcessedEvent event);
}