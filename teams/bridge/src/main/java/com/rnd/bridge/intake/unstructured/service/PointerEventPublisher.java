package com.rnd.bridge.intake.unstructured.service;

import com.rnd.bridge.intake.unstructured.dto.Modality;
import com.rnd.bridge.intake.unstructured.dto.RawObjectEvent;
import com.rnd.bridge.intake.unstructured.dto.ScopeDecision;
import com.rnd.bridge.intake.unstructured.dto.SourceRecord;
import com.rnd.bridge.intake.unstructured.dto.SourceType;
import com.rnd.bridge.intake.unstructured.service.MinioLandingService.LandingResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes RawObjectEvent to bridge.raw.landed. dedupKey format ("sha256:<64 hex>") and
 * modality derivation follow the official schema exactly — see plan.md's schema update.
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
            LandingResult landingResult,
            UUID traceId) {
        String dedupKey = computeDedupKey(record);
        Modality modality = deriveModality(record);

        RawObjectEvent event = RawObjectEvent.create(
                objectKey,
                record.sourceType(),
                record.tenantId(),
                Instant.now(),
                scopeDecision,
                dedupKey,
                modality,
                traceId,
                landingResult.contentType(),
                landingResult.sizeBytes(),
                record.occurredAt());

        kafkaTemplate.send(topic, dedupKey, event);
        return event;
    }

    private String computeDedupKey(SourceRecord record) {
        String input = record.sourceType().name() + "|" + record.tenantId() + "|" + record.naturalId();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
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
        if (record.sourceType() != SourceType.DRIVE_DOC) {
            return Modality.TEXT;
        }
        String mimeType = record.mimeType();
        if (mimeType != null && mimeType.startsWith("image/")) {
            return Modality.IMAGE;
        }
        return Modality.DOCUMENT;
    }
}
