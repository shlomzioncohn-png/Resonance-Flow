package com.rnd.bridge.intake.unstructured.dto;

/**
 * Per the official RawObjectEvent JSON Schema. This lane only ever produces TEXT
 * (email/chat/calendar) or IMAGE/DOCUMENT (Drive, derived from mimeType) — AUDIO/VIDEO
 * belong to the meeting/doc/audio lane.
 */
public enum Modality {
    TEXT,
    IMAGE,
    AUDIO,
    VIDEO,
    DOCUMENT
}
