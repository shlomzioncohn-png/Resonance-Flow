package com.rnd.bridge.raw.model;

/**
 * Matches the official RawObjectEvent JSON Schema's source_type enum exactly. Shared by
 * both unstructured lanes: EMAIL/SLACK/WHATSAPP/DRIVE/CALENDAR are produced by the
 * Unstructured-intake lane (Malki), ZOOM/BIGBLUEBUTTON and SCANNED_DOC by the meeting/doc/audio
 * lane (Esther). Enum constant names must stay uppercase+underscore — they're used verbatim as
 * the first segment of the MinIO object key, which the schema pattern-enforces as [A-Z_]+.
 * <p>
 * Every value names the source platform, so a consumer can build a link back to the original
 * from source_type + source_ref alone - which is why meetings are ZOOM / BIGBLUEBUTTON rather
 * than one shared MEETING value (issue #31).
 */
public enum RawSourceType {
    EMAIL,
    SLACK,
    WHATSAPP,
    DRIVE,
    CALENDAR,
    ZOOM,
    BIGBLUEBUTTON,
    SCANNED_DOC;

    /** True for the meeting platforms - the one place that decides which source types are meetings. */
    public boolean isMeeting() {
        return this == ZOOM || this == BIGBLUEBUTTON;
    }
}
