package com.rnd.bridge.intake.unstructured.dto;

import com.rnd.bridge.shared.unstructured.dto.SourceType;
import java.time.Instant;
import java.util.List;

/**
 * The unit the scoping filter and landing pipeline operate on.
 * For email/doc/calendar sources: one record per raw item.
 * For chat sources: one record per session (a run of ChatMessages grouped by
 * ChatSessionGrouper) — sender holds the joined participant list, subject is null,
 * body is the concatenated transcript used for scoping/keyword matching.
 * participants is the real list backing the schema's participants field — every distinct
 * sender in a chat session, or the single sender/owner/organizer for other sources.
 * rawContent preserves the original fixture content in full (all fields, not just the
 * ones modeled here) so nothing is lost before Mind ever sees the landed object —
 * this lane lands raw content, it doesn't interpret it.
 * mimeType is the MIME type of an attached file (an email/WhatsApp attachment, or a
 * Drive file's own type) — null for plain text — and drives Modality derivation.
 */
public record SourceRecord(
        SourceType sourceType,
        String tenantId,
        String naturalId,
        Instant occurredAt,
        String sender,
        List<String> participants,
        String subject,
        String body,
        String rawContent,
        String mimeType) {
}
