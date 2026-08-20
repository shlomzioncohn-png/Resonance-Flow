package com.rnd.bridge.intake.unstructured.dto;

/**
 * Matches the official RawObjectEvent JSON Schema's sourceType enum, restricted to the
 * values this lane can actually emit (MEETING/SCANNED_DOC belong to the meeting/doc/audio
 * lane). Enum constant names must stay uppercase+underscore — they're used verbatim as the
 * first segment of the MinIO object key, which the schema pattern-enforces as [A-Z_]+.
 */
public enum SourceType {
    EMAIL,
    SLACK,
    WHATSAPP,
    DRIVE_DOC,
    CALENDAR
}
