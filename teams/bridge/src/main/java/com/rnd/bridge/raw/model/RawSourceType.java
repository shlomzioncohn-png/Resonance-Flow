package com.rnd.bridge.raw.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Matches the official RawObjectEvent JSON Schema's source_type enum exactly. Shared by
 * both unstructured lanes: EMAIL/SLACK/WHATSAPP/DRIVE/CALENDAR are produced by the
 * Unstructured-intake lane (Malki), MEETING by the meeting/doc/audio lane (Esther). Enum
 * constant names must stay uppercase+underscore — they're used verbatim (via .name()) as
 * the first segment of the MinIO object key, which the schema pattern-enforces as [A-Z_]+.
 * @JsonProperty overrides only the Kafka wire format to lowercase snake_case, per Mind's
 * request, without touching the object-key-facing .name().
 */
public enum RawSourceType {
    @JsonProperty("email") EMAIL,
    @JsonProperty("slack") SLACK,
    @JsonProperty("whatsapp") WHATSAPP,
    @JsonProperty("drive") DRIVE,
    @JsonProperty("calendar") CALENDAR,
    @JsonProperty("meeting") MEETING
}
