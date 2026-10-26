package com.rnd.bridge.storage;

import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds MinIO object keys as SOURCE_TYPE/tenant_id/yyyy-mm-dd/filename — matches the
 * official schema's object_key pattern (^[A-Z_]+/[^/]+/\d{4}-\d{2}-\d{2}/[^/]+$).
 * RawSourceType.name() already satisfies the [A-Z_]+ requirement.
 * The date segment is the ingestion date (when Bridge landed the object), not the
 * source's own timestamp (SourceRecord.occurredAt) — matches the standard data-lake
 * convention of partitioning a raw-landing bucket by when content arrived, so retention/
 * lifecycle rules can operate on "what came in this month" regardless of how old the
 * source content itself is. The caller passes ingestedAt rather than this class calling
 * Instant.now() itself, so the same instant (AbstractIntakeLane.run()'s asOf) is shared
 * with the MinIO tag and the published event instead of three independent clocks.
 * Filename gets a fresh random id per record. Linking a body record to its attachment
 * record(s) is no longer this class's job — that's now carried explicitly by
 * RawObjectEvent.correlationId/attachmentIndex (see UnstructuredPayloadMapper), so the
 * object_key itself doesn't need to encode it. A side benefit: two attachments on the same
 * message with an identical original filename (e.g. two files both named "invoice.pdf")
 * can't collide, since each gets its own random id regardless of filename.
 */
@Component
public class ObjectKeyBuilder {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    public String build(SourceRecord record, Instant ingestedAt, Role role) {
        String date = DATE_FORMAT.format(ingestedAt);
        String id = UUID.randomUUID().toString();
        String filename = record.attachmentFilename() != null
                ? "%s-%s".formatted(id, record.attachmentFilename())
                : id + "." + primaryExtension(record, role);
        return "%s/%s/%s/%s".formatted(record.sourceType().name(), record.tenantId(), date, filename);
    }

    /** EMAIL's extension is keyed off the record's own mimeType, not just role, because EMAIL has
     * two distinct non-attachment record shapes now, told apart only by what RealEmailSourceReader
     * explicitly sets: the readable-text BODY ("text/plain; charset=UTF-8") and the raw-Gmail-JSON
     * SOURCE_METADATA sibling ("application/json", see SourceRecord.objectRole's javadoc).
     * FixtureEmailSourceReader's body record never sets mimeType (stays null) - deliberately falls
     * through to "json", its original/unchanged extension, so it is NOT touched by this change.
     * Role alone can't make this distinction: both the Real BODY and the Fixture BODY resolve to
     * Role.BODY via SourceRecordRefMapper.roleFor's default inference.
     * DRIVE now also derives its extension from mimeType (see driveExtension) - RealDriveSourceReader
     * sets mimeType to describe what was actually landed (export text, or the real file's own type),
     * never a synthetic marker, so this is safe. FixtureDriveSourceReader never sets mimeType (stays
     * null, intentionally not touched by this change - see driveExtension's default case), so its
     * JSON fixture content keeps the "json" extension it always had.
     * CALENDAR/SLACK/WHATSAPP are deliberately NOT routed through a mimeType check. */
    private String primaryExtension(SourceRecord record, Role role) {
        // Attachments never reach this method - build() branches on attachmentFilename() first -
        // so role here is always BODY or SOURCE_METADATA.
        RawSourceType sourceType = record.sourceType();
        if (sourceType == RawSourceType.EMAIL) {
            String mimeType = record.mimeType();
            return mimeType != null && mimeType.startsWith("text/plain") ? "txt" : "json";
        }
        if (sourceType == RawSourceType.DRIVE) {
            return driveExtension(record.mimeType());
        }
        return switch (sourceType) {
            case EMAIL, DRIVE, CALENDAR -> "json";
            case SLACK, WHATSAPP -> "txt";
            case ZOOM, BIGBLUEBUTTON, SCANNED_DOC -> throw new IllegalArgumentException(
                    sourceType + " is produced by the meeting/doc/audio lane, not this one");
        };
    }

    /** Explicit mimeType -> extension table for DRIVE, so the landed key matches what was
     * actually fetched (export text, a real PDF, a real image, ...) instead of always "json".
     * Deliberately an explicit map, not a "subtype after the slash" derivation - that would turn
     * e.g. "application/vnd.google-apps.document" (never actually landed as record content, but
     * defensive here) or "image/svg+xml" into a bogus extension. Anything not listed here - null,
     * an unrecognized/vendor mimeType, or FixtureDriveSourceReader's always-null mimeType - keeps
     * today's "json" default rather than guessing. */
    private static String driveExtension(String mimeType) {
        if (mimeType == null) {
            return "json";
        }
        return switch (mimeType) {
            case "text/plain" -> "txt";
            case "application/pdf" -> "pdf";
            case "application/json" -> "json";
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "json";
        };
    }
}
