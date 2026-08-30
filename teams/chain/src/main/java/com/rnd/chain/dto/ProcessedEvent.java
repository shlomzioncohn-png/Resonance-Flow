package com.rnd.chain.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Marks that a given event ID has already been claimed/processed by this
 * service, so that receiving the same event more than once does not
 * create duplicate state changes.
 *
 * A record on purpose: a plain, immutable data holder with no inheritance
 * or framework-(de)serialization requirements - it never crosses Jackson
 * (unlike EventEnvelope's payload), it only ever moves between
 * ChainEventConsumer and a ProcessedEventStore implementation in memory.
 * The storage technology behind ProcessedEventStore is still undecided
 * (DB table, Redis, etc.) - once that's picked, this record's shape stays
 * as-is; only the storage implementation changes.
 */
public record ProcessedEvent(UUID eventId, String eventType, Instant processedAt) {
}
