package com.rnd.bridge.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.raw.model.RawSourceType;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Content-hash dedup index for the media lane, stored in MinIO rather than a new
 * datastore - same reasoning plan.md (Malki's lane) uses for cursor persistence
 * (`_cursors/{sourceType}/{tenantId}.json`): it's small control-plane state, not lane
 * content, so it doesn't go through the shared ObjectStorageClient (which is for landing
 * raw content, tagged and content-addressed by object key) - it talks to MinioClient
 * directly, at a path prefix (`_dedup/`) that stays out of the object_key pattern
 * ([A-Z_]+/tenant/yyyy-MM-dd/file) every real landed object follows.
 * <p>
 * Not safe for concurrent runs against the same (sourceType, tenantId) - a load-then-save
 * race could drop a hash written by a concurrent run. Fine for this lane's single-instance
 * manual trigger; would need a lock (or an atomic MinIO conditional write) if that changes.
 */
@Component
public class MediaDedupIndex {

    private static final String PREFIX = "_dedup";

    private final MinioClient minioClient;
    private final ObjectMapper objectMapper;
    private final String bucket;
    private volatile boolean bucketEnsured = false;

    public MediaDedupIndex(MinioClient minioClient, ObjectMapper objectMapper,
                            @Value("${minio.bucket}") String bucket) {
        this.minioClient = minioClient;
        this.objectMapper = objectMapper;
        this.bucket = bucket;
    }

    public Set<String> load(RawSourceType sourceType, String tenantId) {
        ensureBucket();
        String key = indexKey(sourceType, tenantId);
        try (InputStream in = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            String[] hashes = objectMapper.readValue(in, String[].class);
            return new HashSet<>(Set.of(hashes));
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return new HashSet<>();
            }
            throw new IllegalStateException("Failed to read dedup index " + key, e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read dedup index " + key, e);
        }
    }

    public void save(RawSourceType sourceType, String tenantId, Set<String> hashes) {
        ensureBucket();
        String key = indexKey(sourceType, tenantId);
        try {
            byte[] json = objectMapper.writeValueAsBytes(hashes);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(json), json.length, -1)
                    .contentType("application/json")
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write dedup index " + key, e);
        }
    }

    private String indexKey(RawSourceType sourceType, String tenantId) {
        return "%s/%s/%s.json".formatted(PREFIX, sourceType.name(), tenantId);
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
}
