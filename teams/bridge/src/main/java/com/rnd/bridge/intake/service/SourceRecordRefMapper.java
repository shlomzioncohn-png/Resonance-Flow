package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
import com.rnd.bridge.storage.ObjectKeyBuilder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Builds the {@link RawObjectRef} for one {@link SourceRecord} - a pure transform, no I/O:
 * contentType/sizeBytes/contentHash are all derived from the record itself. Shared by
 * {@code UnstructuredPayloadMapper} (which builds a whole logical item's object list, one ref
 * per record in a correlationId-group) and {@code SlackSessionPublisher} (which needs this same
 * per-record logic for just the transcript, at session close, outside the generic grouped lane -
 * see that class's javadoc for why).
 */
@Service
public class SourceRecordRefMapper {

    private final ObjectKeyBuilder objectKeyBuilder;
    private final IntakeLandingService landingService;

    public SourceRecordRefMapper(ObjectKeyBuilder objectKeyBuilder, IntakeLandingService landingService) {
        this.objectKeyBuilder = objectKeyBuilder;
        this.landingService = landingService;
    }

    public RawObjectRef toRef(SourceRecord record, Instant ingestedAt) {
        if (record.precomputedRef() != null) {
            // Already landed under a different group before this record was built (see
            // SourceRecord.precomputedRef's javadoc) - reuse the exact ref instead of deriving a
            // new one from rawContent, which is unused filler for this record.
            return record.precomputedRef();
        }
        String objectKey = objectKeyBuilder.build(record, ingestedAt);
        String contentType = landingService.resolveContentType(record, objectKey);
        return new RawObjectRef(
                roleFor(record), objectKey, computeContentHash(record), contentType,
                (long) record.rawContent().length, deriveModality(record));
    }

    /** BODY for a standalone item; TRANSCRIPT for a chat session's own transcript record
     * (index 0 on SLACK/WHATSAPP); ATTACHMENT for every other attachment record. */
    private Role roleFor(SourceRecord record) {
        if (record.attachmentIndex() > 0) {
            return Role.ATTACHMENT;
        }
        boolean isChatTranscript = record.sourceType() == RawSourceType.SLACK
                || record.sourceType() == RawSourceType.WHATSAPP;
        return isChatTranscript ? Role.TRANSCRIPT : Role.BODY;
    }

    private String computeContentHash(SourceRecord record) {
        byte[] content = record.rawContent();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private Modality deriveModality(SourceRecord record) {
        String mimeType = record.mimeType();
        if (mimeType == null) {
            return Modality.TEXT;
        }
        if (mimeType.startsWith("audio/")) {
            return Modality.AUDIO;
        }
        if (mimeType.startsWith("image/") || mimeType.equals("application/pdf")) {
            return Modality.SCANNED_DOC;
        }
        return Modality.TEXT;
    }
}
