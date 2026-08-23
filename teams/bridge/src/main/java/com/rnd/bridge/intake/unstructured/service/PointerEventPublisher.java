package com.rnd.bridge.intake.unstructured.service;

import com.rnd.bridge.intake.unstructured.dto.SourceRecord;
import com.rnd.bridge.intake.unstructured.service.MinioLandingService.LandingResult;
import com.rnd.bridge.shared.unstructured.dto.Modality;
import com.rnd.bridge.shared.unstructured.dto.RawObjectEvent;
import com.rnd.bridge.shared.unstructured.dto.ScopeDecision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
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
 */
@Service
public class PointerEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public PointerEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${bridge.intake.raw-landed-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public RawObjectEvent publish(
            String objectKey,
            SourceRecord record,
            ScopeDecision scopeDecision,
            LandingResult landingResult) {
        String contentHash = computeContentHash(record);
        Modality modality = deriveModality(record);

        RawObjectEvent event = RawObjectEvent.create(
                objectKey,
                record.sourceType(),
                record.tenantId(),
                Instant.now(),
                scopeDecision,
                contentHash,
                modality,
                record.participants(),
                landingResult.contentType(),
                landingResult.sizeBytes(),
                record.occurredAt());

        kafkaTemplate.send(topic, contentHash, event);
        return event;
    }

    private String computeContentHash(SourceRecord record) {
        byte[] content = record.rawContent().getBytes(StandardCharsets.UTF_8);
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
