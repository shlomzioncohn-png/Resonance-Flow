package com.rnd.bridge.storage;

import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.RawSourceType;
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

    public String build(SourceRecord record, Instant ingestedAt) {
        String date = DATE_FORMAT.format(ingestedAt);
        String id = UUID.randomUUID().toString();
        String filename = record.attachmentFilename() != null
                ? "%s-%s".formatted(id, record.attachmentFilename())
                : id + "." + primaryExtension(record.sourceType());
        return "%s/%s/%s/%s".formatted(record.sourceType().name(), record.tenantId(), date, filename);
    }

    private String primaryExtension(RawSourceType sourceType) {
        return switch (sourceType) {
            case EMAIL, DRIVE, CALENDAR -> "json";
            case SLACK, WHATSAPP -> "txt";
            case MEETING, SCANNED_DOC -> throw new IllegalArgumentException(
                    sourceType + " is produced by the meeting/doc/audio lane, not this one");
        };
    }
}
