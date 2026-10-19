package com.rnd.bridge.intake.model;

/**
 * Persisted identity/revision state for one Google Drive file, letting
 * DriveFileStateReconciler recognize the same file across separate incremental syncs and keep
 * its correlationId/revision stable instead of minting a fresh UUID and revision 0 on every
 * poll - the direct Drive analog of {@link CalendarEventState} (see its javadoc for the full
 * reasoning this mirrors): fileId plays eventId's role (storage key only, never exposed
 * downstream) and headRevisionId plays etag's role (Google's own per-version change marker for
 * this file - a new revision is minted specifically when content changes, not on rename/move/
 * permission changes, per Google Drive API's own documented behavior for this field).
 * correlationId is the separate Bridge-minted UUID that IS exposed (on SourceRecord/
 * RawObjectEvent), minted once when the file is first seen and copied forward unchanged on
 * every later revision.
 * revision increments only when headRevisionId actually changes, mirroring
 * CalendarEventState.revision's contract (an unchanged retry never advances it).
 * published means the SourceRecord built from this exact revision has actually reached Kafka -
 * confirmed by DriveFileStateStore.confirmPublished only, the same expected-revision-gated
 * pattern as CalendarEventStateStore.confirmPublished. Reset to false whenever the revision
 * advances.
 */
public record DriveFileState(
        String fileId, String tenantId, String correlationId, String headRevisionId, int revision,
        boolean published) {
}
