package com.rnd.bridge.raw.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * One physical object landed in MinIO, as an entry of {@link RawObjectEvent#objects()}.
 * Field names mirror what used to sit directly on {@link RawObjectEvent} itself
 * (object_key/content_hash/content_type/size_bytes/modality) - they moved here so every
 * part of a logical item (not just the primary one) gets its own hash/size/type/modality,
 * instead of only the first object having them and the rest being bare pointer fields
 * (transcript_object_key, the former additional_metadata_object_key).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RawObjectRef(
        Role role,
        String objectKey,
        String contentHash,
        String contentType,
        Long sizeBytes,
        Modality modality) {
}
