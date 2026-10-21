package com.rnd.bridge.raw.model;

/**
 * Per the official RawObjectEvent JSON Schema: a routing hint for which AI engine should
 * process one {@link RawObjectRef} (ASR for audio, OCR for scanned documents/images) — not a
 * general content-type classifier. Images are treated as SCANNED_DOC deliberately: a
 * photographed document or receipt is common enough that skipping OCR on images would risk
 * missing real content.
 * <p>
 * TEXT is for a part that's already plain text/structured data - a transcript, a
 * platform-native metadata sidecar, an email body - and needs neither AI engine. Added
 * alongside the move from one event-level modality to one per {@link RawObjectRef}: under
 * the old single-object shape there was no object to attach TEXT to for a transcript sidecar
 * (it wasn't its own RawObjectEvent), so the question never came up.
 * <p>
 * Wire format is the enum name verbatim (TEXT/AUDIO/SCANNED_DOC), like every other enum on this
 * event - Mind asked for uppercase SNAKE_CASE (issue #31, 2026-09-06).
 */
public enum Modality {
    TEXT,
    AUDIO,
    SCANNED_DOC
}
