package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.RawObjectValidator;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.ScopeDecision;
import com.rnd.bridge.storage.ObjectStorageClient.PutResult;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes RawObjectEvent to bridge.raw.landed. contentHash is a real SHA-256 of the raw
 * landed bytes (schema format "sha256:<64 hex>") — identical content always hashes the
 * same, regardless of when/how it was published. modality is a routing hint for an
 * attached file's AI engine (ASR for audio, OCR for scanned docs/images), derived from
 * SourceRecord.mimeType — which is only set when the record actually has an attachment
 * (an email/WhatsApp attachment, or a Drive file's own type); null (no hint) for plain
 * text. Not Drive-specific: an audio voice note on WhatsApp gets AUDIO exactly the same
 * way a Drive audio file would.
 * Building the event and sending it are separate steps on purpose, so the
 * {@link RawObjectValidator} gate runs in between and can actually reject a malformed
 * event before it reaches Kafka. Not yet wired into the ★★ publish/kafka
 * PayloadPublisher<O> abstraction — this still talks to KafkaTemplate directly, pending a
 * team decision on that interface's shape (topic/key strategy is per-lane today).
 */
@Service
public class PointerEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RawObjectValidator validator;
    private final String topic;

    public PointerEventPublisher(
            @Qualifier("kafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
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
        kafkaTemplate.send(topic, event.contentHash(), event);
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

        return RawObjectEvent.create(
                objectKey,
                record.sourceType(),
                record.tenantId(),
                ingestedAt,
                scopeDecision,
                contentHash,
                modality,
                record.participants(),
                putResult.contentType(),
                putResult.sizeBytes(),
                record.occurredAt(),
                record.correlationId(),
                record.attachmentIndex());
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
