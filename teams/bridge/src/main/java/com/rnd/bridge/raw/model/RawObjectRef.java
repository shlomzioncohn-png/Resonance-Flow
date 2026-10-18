package com.rnd.bridge.raw.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * One MinIO object belonging to a {@link RawObjectEvent}'s logical item - the list-based
 * replacement for the old flat object_key/content_hash/content_type/size_bytes/
 * attachment_index/attachment_count/transcript_object_key fields (see RawObjectEvent's
 * javadoc for the migration). role/objectKey/contentHash/contentType/modality are all
 * required; sizeBytes stays nullable, same as it was on the outer event, since not every
 * producer always has it at mapping time.
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
