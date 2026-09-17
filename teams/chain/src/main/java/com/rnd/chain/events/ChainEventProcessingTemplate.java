package com.rnd.chain.events;

import com.rnd.chain.dto.ProcessedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared claim/logging skeleton for Chain's per-topic Kafka listeners, factored out of what used
 * to be ChainEventConsumer's single generic dispatch-by-eventType logic (see the retired
 * EventEnvelope/EventHandler/ChainEventConsumer trio - every event type now has its own listener,
 * so cross-type dispatch is no longer needed, only the claim/transaction/logging plumbing is).
 *
 * Two idempotency strategies exist (see ProcessedEventStore's Javadoc and PLAN.md decision D2) -
 * pick the method matching the event's own contract, don't default to one:
 * - Events that carry their own event id (Mind's events, e.g. task.dependencies.proposed): use
 *   {@link #processClaimed}, which performs the tryClaim/release dance around the given action.
 * - Events with no event id, handled state-based instead (e.g. task.updated - see
 *   TaskUpdatedEventHandler): use {@link #processUnclaimed}, which just runs the action and logs
 *   the outcome - the action itself must already be safe to run more than once.
 */
@Component
public class ChainEventProcessingTemplate {

    private static final Logger log = LoggerFactory.getLogger(ChainEventProcessingTemplate.class);

    private final ProcessedEventStore processedEventStore;

    public ChainEventProcessingTemplate(ProcessedEventStore processedEventStore) {
        this.processedEventStore = processedEventStore;
    }

    /**
     * Runs {@code action} only if {@code eventId} is not already claimed - a duplicate delivery
     * is logged and skipped, action is never invoked twice for the same id. On any
     * RuntimeException from {@code action}, releases the claim (so a retry or later redelivery
     * can attempt it again) before rethrowing, so ChainKafkaErrorHandlingConfig's error handler
     * (or a topic-based non-blocking retry) can act on it. See ProcessedEventStore's Javadoc for
     * exactly what a claim does and doesn't guarantee.
     */
    public void processClaimed(UUID eventId, String eventType, Runnable action) {
        if (!processedEventStore.tryClaim(new ProcessedEvent(eventId, eventType, Instant.now()))) {
            log.info("Event [{}] of type [{}] was already claimed - skipping (duplicate)", eventId, eventType);
            return;
        }
        try {
            action.run();
            log.info("Event [{}] of type [{}] processed", eventId, eventType);
        } catch (RuntimeException e) {
            processedEventStore.release(eventId);
            log.warn("Event [{}] of type [{}] failed, claim released: {}", eventId, eventType, e.getMessage());
            throw e;
        }
    }

    /**
     * Runs {@code action} for an event type that has no event id to claim against - no dedup
     * table involved, {@code action} itself must be idempotent (e.g. a state-based,
     * last-write-wins update, per TaskUpdatedEventHandler).
     */
    public void processUnclaimed(String correlationKey, String eventType, Runnable action) {
        try {
            action.run();
            log.info("Event of type [{}] (key={}) processed", eventType, correlationKey);
        } catch (RuntimeException e) {
            log.warn("Event of type [{}] (key={}) failed: {}", eventType, correlationKey, e.getMessage());
            throw e;
        }
    }
}
