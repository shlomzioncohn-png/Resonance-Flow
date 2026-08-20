package com.rnd.bridge.intake.unstructured.dto;

import java.time.Instant;

/**
 * The unit the scoping filter and landing pipeline operate on.
 * For email/doc/calendar sources: one record per raw item.
 * For chat sources: one record per session (a run of ChatMessages grouped by
 * ChatSessionGrouper) — sender holds the joined participant list, subject is null,
 * body is the concatenated transcript used for scoping/keyword matching.
 * rawContent preserves the original fixture content in full (all fields, not just the
 * ones modeled here) so nothing is lost before Mind ever sees the landed object —
 * this lane lands raw content, it doesn't interpret it.
 * mimeType is only populated for DRIVE_DOC records; drives Modality derivation.
 */
public record SourceRecord(
        SourceType sourceType,
        String tenantId,
        String naturalId,
        Instant occurredAt,
        String sender,
        String subject,
        String body,
        String rawContent,
        String mimeType) {
}
