package com.rnd.bridge.shared.unstructured.dto;

/**
 * Matches the official RawObjectEvent JSON Schema's source_type enum exactly. Shared by
 * both unstructured lanes: EMAIL/SLACK/WHATSAPP/DRIVE/CALENDAR are produced by the
 * Unstructured-intake lane (Malki), MEETING by the meeting/doc/audio lane (Esther). Enum
 * constant names must stay uppercase+underscore — they're used verbatim as the first
 * segment of the MinIO object key, which the schema pattern-enforces as [A-Z_]+.
 */
public enum SourceType {
    EMAIL,
    SLACK,
    WHATSAPP,
    DRIVE,
    CALENDAR,
    MEETING
}
