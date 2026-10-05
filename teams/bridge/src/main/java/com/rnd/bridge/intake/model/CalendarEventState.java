package com.rnd.bridge.intake.model;

/**
 * Persisted identity/revision state for one Google Calendar event, letting
 * CalendarEventStateReconciler recognize the same event across separate incremental syncs and
 * keep its correlationId/revision stable instead of minting a fresh UUID and revision 0 on
 * every poll (see CalendarEventStateReconciler's javadoc).
 * eventId is Google's own event.id - the stable per-event identity Calendar's syncToken deltas
 * key on - used only as this record's storage key (see CalendarEventStateStore), never exposed
 * downstream; correlationId is the separate Bridge-minted UUID that IS exposed (on
 * SourceRecord/RawObjectEvent), minted once when the event is first seen and copied forward
 * unchanged on every later revision - same correlationId-vs-internal-id split as GroupState
 * uses for chat groups (see GroupState's javadoc), kept as a logically separate model here
 * rather than reusing GroupState/MessageSnapshot, which mean something different (a chat
 * session's accumulated messages) and have no equivalent of etag-based change detection.
 * etag is Google's own per-version marker for this event (GenericJson.getEtag() - changes
 * whenever Google actually mutates the event, stays the same on a plain re-read) - the smallest
 * reliable signal to tell a retry of the same Calendar version (etag unchanged) from a
 * genuinely newer one (etag changed) without diffing the event body. Cheaper and more precise
 * than comparing Event.getUpdated() timestamps, which Google can in principle touch without a
 * meaningful content change.
 * revision increments only when etag actually changes, mirroring GroupState.revision's contract
 * (an unchanged retry never advances it) - see CalendarEventStateReconciler.resolve for the
 * full decision.
 * published means the SourceRecord built from this exact revision has actually reached Kafka -
 * confirmed by CalendarEventStateStore.confirmPublished only, the same expected-revision-gated
 * pattern as GroupStateStore.confirmPublished, so a stale ack for a since-superseded revision
 * can never mark a newer, not-yet-published revision as confirmed. Reset to false whenever the
 * revision advances (a new version is unconfirmed until proven otherwise).
 */
public record CalendarEventState(
        String eventId, String tenantId, String correlationId, String etag, int revision, boolean published) {
}
