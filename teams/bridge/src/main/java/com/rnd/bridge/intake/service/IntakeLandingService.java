package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.ScopeDecision;
import com.rnd.bridge.storage.ObjectStorageClient;
import com.rnd.bridge.storage.ObjectStorageClient.PutResult;
import com.rnd.bridge.storage.ObjectTags;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Lands a SourceRecord's raw bytes through the shared ObjectStorageClient, tagging the
 * object with source_type/tenant_id/ingested_at/scope_decision (see ObjectTags). Replaces
 * the old MinioLandingService: the actual MinIO put now lives behind ObjectStorageClient
 * (storage/), which is intake-agnostic so the media lane can reuse it without depending
 * on SourceRecord.
 */
@Service
public class IntakeLandingService {

    private final ObjectStorageClient storageClient;

    public IntakeLandingService(@Qualifier("minioObjectStorageClient") ObjectStorageClient storageClient) {
        this.storageClient = storageClient;
    }

    public PutResult land(String objectKey, SourceRecord record, ScopeDecision scopeDecision, Instant ingestedAt) {
        String contentType = resolveContentType(record, objectKey);
        return storageClient.putObject(
                objectKey,
                record.rawContent(),
                contentType,
                ObjectTags.of(record.sourceType(), record.tenantId(), ingestedAt, scopeDecision));
    }

    /**
     * Pure - callers that already need the content type before landing (e.g. to build the
     * event that references it) don't have to wait on the actual I/O in {@link #land} to get it.
     */
    public String resolveContentType(SourceRecord record, String objectKey) {
        if (record.mimeType() != null) {
            return record.mimeType();
        }
        return objectKey.endsWith(".json") ? "application/json" : "text/plain; charset=utf-8";
    }
}
