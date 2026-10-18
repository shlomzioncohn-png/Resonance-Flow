package com.rnd.bridge.raw.model;

/**
 * Matches the official RawObjectEvent JSON Schema's source_type enum exactly. Shared by
 * both unstructured lanes: EMAIL/SLACK/WHATSAPP/DRIVE/CALENDAR are produced by the
 * Unstructured-intake lane (Malki), MEETING and SCANNED_DOC by the meeting/doc/audio lane
 * (Esther). Enum constant names must stay uppercase+underscore — they're used verbatim (via
 * .name()) both as the first segment of the MinIO object key ([A-Z_]+, schema-enforced) and
 * as the Kafka wire format, per Mind's request (2026-09).
 */
public enum RawSourceType {
    EMAIL,
    SLACK,
    WHATSAPP,
    DRIVE,
    CALENDAR,
    MEETING,
    SCANNED_DOC,
    // Contract value only - no reader/mapper produces this yet; see Mind's agreed
    // RawObjectEvent contract. Do not wire a mapping to it without a real source.
    DOC
}
