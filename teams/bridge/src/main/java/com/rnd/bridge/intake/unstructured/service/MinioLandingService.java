package com.rnd.bridge.intake.unstructured.service;

import com.rnd.bridge.intake.unstructured.dto.SourceRecord;
import com.rnd.bridge.shared.unstructured.dto.ScopeDecision;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Bucket existence is checked/created lazily on first upload, not eagerly at startup —
 * this keeps app startup and mvn test working without a live MinIO instance (see
 * plan.md's "Design constraint").
 */
@Service
public class MinioLandingService {

    private final MinioClient minioClient;
    private final String bucket;
    private volatile boolean bucketEnsured = false;

    public MinioLandingService(MinioClient minioClient, @Value("${minio.bucket}") String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    public LandingResult land(String objectKey, SourceRecord record, ScopeDecision scopeDecision) {
        ensureBucket();

        byte[] content = record.rawContent().getBytes(StandardCharsets.UTF_8);
        String contentType = resolveContentType(record, objectKey);

        try (InputStream in = new ByteArrayInputStream(content)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, content.length, -1)
                    .contentType(contentType)
                    .tags(Map.of(
                            "source_type", record.sourceType().name(),
                            "tenant_id", record.tenantId(),
                            "ingested_at", Instant.now().toString(),
                            "scope_decision", scopeDecision.name()))
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to land object " + objectKey + " in MinIO", e);
        }

        return new LandingResult(contentType, content.length);
    }

    private synchronized void ensureBucket() {
        if (bucketEnsured) {
            return;
        }
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            bucketEnsured = true;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to ensure MinIO bucket " + bucket, e);
        }
    }

    private String resolveContentType(SourceRecord record, String objectKey) {
        if (record.mimeType() != null) {
            return record.mimeType();
        }
        return objectKey.endsWith(".json") ? "application/json" : "text/plain; charset=utf-8";
    }

    public record LandingResult(String contentType, long sizeBytes) {
    }
}
