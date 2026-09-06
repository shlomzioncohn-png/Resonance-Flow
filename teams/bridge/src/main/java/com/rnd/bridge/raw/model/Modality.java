package com.rnd.bridge.raw.model;

/**
 * Per the official RawObjectEvent JSON Schema: a routing hint for which AI engine should
 * process an attached file (ASR for audio, OCR for scanned documents/images) — not a
 * general content-type classifier. Optional; null when the record has no such attachment
 * (plain text). Images are treated as SCANNED_DOC deliberately: a photographed document
 * or receipt is common enough that skipping OCR on images would risk missing real content.
 * Wire format is the enum name verbatim (uppercase snake_case), per Mind's request (2026-09).
 */
public enum Modality {
    AUDIO,
    SCANNED_DOC
}
