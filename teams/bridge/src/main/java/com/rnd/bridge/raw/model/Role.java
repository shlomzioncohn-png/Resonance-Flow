package com.rnd.bridge.raw.model;

/**
 * What one {@link RawObjectRef} is, within the logical item its {@link RawObjectEvent}
 * represents. Generic across both unstructured lanes - not a per-source enum - so a new
 * source never needs a new role unless it's genuinely a new kind of part:
 * BODY/ATTACHMENT (Malki's email/chat lane: the message text, and any files attached to
 * it) and RECORDING/TRANSCRIPT/SOURCE_METADATA (Esther's meeting lane: the Zoom/BBB
 * recording itself, its transcript, and the platform's own meeting report).
 */
public enum Role {
    BODY,
    ATTACHMENT,
    TRANSCRIPT,
    RECORDING,
    SOURCE_METADATA
}
