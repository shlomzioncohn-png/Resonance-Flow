package com.rnd.bridge.raw.model;

/**
 * Per the official RawObjectEvent JSON Schema: a routing hint for which AI engine should
 * process an attached file (ASR for audio, OCR for scanned documents/images) — not a
 * general content-type classifier. TEXT is the explicit default for plain text records
 * (no such attachment), per Mind's request (2026-09) that this be sent as an explicit
 * enum value rather than a null field. Images are treated as SCANNED_DOC deliberately: a
 * photographed document or receipt is common enough that skipping OCR on images would risk
 * missing real content. Wire format is the enum name verbatim (uppercase snake_case), per
 * Mind's request (2026-09).
 */
public enum Modality {
    TEXT,
    AUDIO,
    SCANNED_DOC
}
