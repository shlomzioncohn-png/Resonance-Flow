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
 * revision increments only when the file's change signal actually changes, mirroring
 * CalendarEventState.revision's contract (an unchanged retry never advances it). Which field is
 * that signal depends on the file: headRevisionId for ordinary (binary-content) files, but
 * Google never populates headRevisionId for a native Google Doc/Sheet/Slide
 * (application/vnd.google-apps.* - verified against Google's own Drive API docs), so for those
 * files version (a separate, Google-maintained monotonically increasing counter, bumped on any
 * content or metadata change) is the signal instead - see DriveFileStateReconciler.resolve.
 * version is nullable and absent on older persisted records from before this field existed; a
 * record with a stored version of null transitioning to a non-null incoming version is treated
 * as a genuine change (not an "unchanged retry") so a native file doesn't stay stuck at its
 * pre-upgrade revision forever - see DriveFileStateReconciler.versionUnchanged.
 * published means the SourceRecord built from this exact revision has actually reached Kafka -
 * confirmed by DriveFileStateStore.confirmPublished only, the same expected-revision-gated
 * pattern as CalendarEventStateStore.confirmPublished. Reset to false whenever the revision
 * advances.
 */
public record DriveFileState(
        String fileId, String tenantId, String correlationId, String headRevisionId, Long version, int revision,
        boolean published) {
}
