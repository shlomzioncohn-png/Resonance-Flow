package com.rnd.bridge.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.core.KeyedLock;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Atomic read-modify-write for small control-plane JSON blobs kept in MinIO — a dedup index, a
 * future sync cursor (see plan.md's "Cursor persistence") — shared by both raw-landing lanes
 * (Malki, Esther; same pairing as {@link ObjectStorageClient}). Deliberately exposes only
 * {@link #update}, not separate load/save methods: a caller can only touch this kind of state
 * through one call that loads, lets the caller mutate, and saves back — all while holding
 * {@link KeyedLock} for the key — so it is not possible to read without locking, or to save
 * without having loaded first. This is what actually fixes the load-then-save race that plain
 * load()/save() methods allow (see media.MediaDedupIndex, which used to expose them directly).
 * <p>
 * Only serializes access within this one JVM instance — see {@link KeyedLock}'s own doc for why
 * that is enough today and what a multi-instance deployment would still need.
 */
@Component
public class ControlPlaneStateStore {

    private final MinioClient minioClient;
    private final ObjectMapper objectMapper;
    private final KeyedLock lock;
    private final String bucket;
    private volatile boolean bucketEnsured = false;

    public ControlPlaneStateStore(MinioClient minioClient, ObjectMapper objectMapper, KeyedLock lock,
                                   @Value("${minio.bucket}") String bucket) {
        this.minioClient = minioClient;
        this.objectMapper = objectMapper;
        this.lock = lock;
        this.bucket = bucket;
    }

    /**
     * Loads the JSON value at key (or emptyValue if nothing has been saved there yet), applies
     * mutator, and writes the result back to the same key — all under one lock held for the
     * full load+mutate+save cycle, so no other caller using the same key can observe or clobber
     * a state in between. Returns the value that was written.
     */
    public <T> T update(String key, Class<T> type, T emptyValue, UnaryOperator<T> mutator) {
        AtomicReference<T> result = new AtomicReference<>();
        lock.run(key, () -> {
            T current = load(key, type, emptyValue);
            T updated = mutator.apply(current);
            save(key, updated);
            result.set(updated);
        });
        return result.get();
    }

    private <T> T load(String key, Class<T> type, T emptyValue) {
        ensureBucket();
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return objectMapper.readValue(in, type);
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return emptyValue;
            }
            throw new IllegalStateException("Failed to read control-plane state " + key, e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read control-plane state " + key, e);
        }
    }

    private void save(String key, Object value) {
        ensureBucket();
        try {
            byte[] json = objectMapper.writeValueAsBytes(value);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(json), json.length, -1)
                    .contentType("application/json")
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write control-plane state " + key, e);
        }
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
