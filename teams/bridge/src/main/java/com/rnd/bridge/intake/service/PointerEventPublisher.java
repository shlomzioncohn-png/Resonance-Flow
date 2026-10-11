package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.RawObjectValidator;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.Role;
import com.rnd.bridge.raw.model.ScopeDecision;
import com.rnd.bridge.storage.ObjectStorageClient.PutResult;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes RawObjectEvent to bridge.raw.landed. contentHash is a real SHA-256 of the raw
 * landed bytes (schema format "sha256:<64 hex>") — identical content always hashes the
 * same, regardless of when/how it was published. modality is a routing hint for this
 * object's AI engine (ASR for audio, OCR for scanned docs/images), derived from
 * SourceRecord.mimeType — which is only set when the record actually has an attachment
 * (an email/WhatsApp attachment, or a Drive file's own type); null (no hint) for plain
 * text. Not Drive-specific: an audio voice note on WhatsApp gets AUDIO exactly the same
 * way a Drive audio file would.
 * <p>
 * Mechanical adaptation to the shared "one event, objects[] list" shape (see
 * {@link RawObjectEvent}'s javadoc, issue #31): each call still publishes its own
 * SourceRecord as its own event with a single-entry objects[] (role BODY for the
 * message/transcript text, ATTACHMENT for a file attached to it) rather than collecting all
 * of a logical item's SourceRecords into one event first - this lane still produces one
 * Kafka message per SourceRecord, same as before. Doing the real collect-then-publish-once
 * rewrite that the new shape is meant for is a bigger, Malki-owned change to
 * UnstructuredIntakeService's grouping, not done here.
 * revision/changeType/changeSignificanceScore are hardcoded to "brand-new item" (0/UPSERT/
 * 100) for the same reason - no revision tracking exists yet on this lane either.
 * <p>
 * Building the event and sending it are separate steps on purpose, so the
 * {@link RawObjectValidator} gate runs in between and can actually reject a malformed
 * event before it reaches Kafka. Not yet wired into the ★★ publish/kafka
 * PayloadPublisher<O> abstraction — this still talks to KafkaTemplate directly, pending a
 * team decision on that interface's shape (topic/key strategy is per-lane today).
 */
@Service
public class PointerEventPublisher {

    private static final int NEW_ITEM_REVISION = 0;
    private static final int NEW_ITEM_SIGNIFICANCE_SCORE = 100;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RawObjectValidator validator;
    private final String topic;

    public PointerEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            RawObjectValidator validator,
            @Value("${bridge.intake.raw-landed-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.validator = validator;
        this.topic = topic;
    }

    public RawObjectEvent publish(
            String objectKey,
            SourceRecord record,
            ScopeDecision scopeDecision,
            PutResult putResult,
            Instant ingestedAt) {
        RawObjectEvent event = buildEvent(objectKey, record, scopeDecision, putResult, ingestedAt);
        validator.validate(event);
        kafkaTemplate.send(topic, event.dedupKey(), event);
        return event;
    }

    private RawObjectEvent buildEvent(
            String objectKey,
            SourceRecord record,
            ScopeDecision scopeDecision,
            PutResult putResult,
            Instant ingestedAt) {
        String contentHash = computeContentHash(record);
        Modality modality = deriveModality(record);
        Role role = record.attachmentIndex() == 0 ? Role.BODY : Role.ATTACHMENT;

        RawObjectRef object = new RawObjectRef(
                role, objectKey, contentHash, putResult.contentType(), putResult.sizeBytes(), modality);

        return RawObjectEvent.create(
                record.sourceType(),
                record.tenantId(),
                record.correlationId(),
                ingestedAt,
                scopeDecision,
                NEW_ITEM_REVISION,
                ChangeType.UPSERT,
                NEW_ITEM_SIGNIFICANCE_SCORE,
                record.participants(),
                record.occurredAt(),
                null,
                List.of(object));
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
            return null;
        }
        if (mimeType.startsWith("audio/")) {
            return Modality.AUDIO;
        }
        if (mimeType.startsWith("image/") || mimeType.equals("application/pdf")) {
            return Modality.SCANNED_DOC;
        }
        return null;
    }
}
