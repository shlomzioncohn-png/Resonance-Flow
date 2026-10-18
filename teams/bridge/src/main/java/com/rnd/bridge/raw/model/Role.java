package com.rnd.bridge.raw.model;

/**
 * What a single {@link RawObjectRef} inside a RawObjectEvent's object list represents.
 * BODY is the primary text content of a non-chat source; TRANSCRIPT is a chat
 * session's concatenated text or a media recording's sidecar transcript; RECORDING is
 * the primary audio/video file for the meeting/doc/audio lane; SOURCE_METADATA is
 * reserved for a future source-generated metadata sidecar, not emitted by any
 * producer yet.
 */
public enum Role {
    BODY,
    ATTACHMENT,
    TRANSCRIPT,
    RECORDING,
    SOURCE_METADATA
}
