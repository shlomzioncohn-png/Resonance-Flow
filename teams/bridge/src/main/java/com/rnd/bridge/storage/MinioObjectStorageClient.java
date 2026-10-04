package com.rnd.bridge.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Bucket existence is checked/created lazily on first upload, not eagerly at startup —
 * this keeps app startup and mvn test working without a live MinIO instance (see
 * plan.md's "Design constraint").
 */
@Service
public class MinioObjectStorageClient implements ObjectStorageClient {

    private final MinioClient minioClient;
    private final String bucket;
    private volatile boolean bucketEnsured = false;

    public MinioObjectStorageClient(MinioClient minioClient, @Value("${minio.bucket}") String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    @Override
    public PutResult putObject(String objectKey, byte[] content, String contentType, Map<String, String> tags) {
        ensureBucket();

        try (InputStream in = new ByteArrayInputStream(content)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, content.length, -1)
                    .contentType(contentType)
                    .tags(tags)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to land object " + objectKey + " in MinIO", e);
        }

        return new PutResult(contentType, content.length);
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
