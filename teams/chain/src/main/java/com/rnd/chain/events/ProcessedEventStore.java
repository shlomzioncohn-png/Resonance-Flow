package com.rnd.chain.events;

import com.rnd.chain.dto.ProcessedEvent;

import java.util.UUID;

/**
 * Tracks which event IDs are currently claimed or already processed, and
 * provides the atomic operation the Consumer needs to do that safely under
 * concurrency.
 *
 * tryClaim is an atomic claim, not a plain check-then-write: it returns
 * true only for the caller that is the first to claim a given event id
 * while it is unclaimed, and false for every other caller - including a
 * concurrent caller racing the exact same instant, and a later redelivery
 * of an event whose handler already succeeded. The Consumer must only call
 * handler.handle(...) after tryClaim returns true - this is what closes
 * the race where two listener threads (consumer concurrency > 1, or a
 * duplicate delivery landing on two different partitions) could otherwise
 * both see "not yet claimed" and both run the handler.
 *
 * If handling then fails, the Consumer calls release(eventId) so the claim
 * is given up and a retry (whether DefaultErrorHandler retrying the same
 * delivery, or a later redelivery) can claim and attempt the event again -
 * a claim is only meant to become permanent once handling actually
 * succeeds, not merely attempted.
 *
 * What this does NOT guarantee: if the process crashes after tryClaim
 * returns true but before either release() or a successful handle()
 * completes, the claim is never released - a later redelivery of that
 * same event will see it as already claimed and skip it, rather than
 * retrying it. Closing that gap for good requires tying the claim to
 * whatever the handler actually persists, in the same transaction - which
 * needs a real persistence decision Chain hasn't made yet (see
 * InMemoryProcessedEventStore's own Javadoc, and PLAN.md decision D2).
 * Handlers registered with this consumer should be written with that in
 * mind: safe to be skipped after a crash, not dependent on being retried.
 *
 * The Consumer depends only on this interface, not on how it is stored -
 * so the actual storage (DB table, Redis, etc.) can be decided and
 * plugged in later without changing the Consumer at all.
 */
public interface ProcessedEventStore {

    boolean tryClaim(ProcessedEvent event);

    void release(UUID eventId);
}
