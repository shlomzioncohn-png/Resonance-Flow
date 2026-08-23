package com.rnd.bridge.intake.unstructured.service;

import com.rnd.bridge.intake.unstructured.dto.SourceRecord;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds MinIO object keys as SOURCE_TYPE/tenant_id/yyyy-mm-dd/uuid.ext — matches the
 * official schema's objectKey pattern (^[A-Z_]+/[^/]+/\d{4}-\d{2}-\d{2}/[^/]+$).
 * SourceType.name() already satisfies the [A-Z_]+ requirement.
 */
@Component
public class ObjectKeyBuilder {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    public String build(SourceRecord record) {
        String date = DATE_FORMAT.format(record.occurredAt());
        String extension = switch (record.sourceType()) {
            case EMAIL, DRIVE, CALENDAR -> "json";
            case SLACK, WHATSAPP -> "txt";
            case MEETING -> throw new IllegalArgumentException(
                    record.sourceType() + " is produced by the meeting/doc/audio lane, not this one");
        };
        return "%s/%s/%s/%s.%s".formatted(
                record.sourceType().name(), record.tenantId(), date, UUID.randomUUID(), extension);
    }
}
