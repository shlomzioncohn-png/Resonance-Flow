package com.rnd.bridge.storage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.core.KeyedLock;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.Headers;

/**
 * A ControlPlaneStateStore backed by an in-memory fake MinioClient instead of a running MinIO -
 * mirrors ControlPlaneStateStoreTest's own setup. ControlPlaneStateStore talks to MinioClient
 * directly rather than through ObjectStorageClient, so InMemoryObjectStorageClient can't stand
 * in for it; any test that needs real load-mutate-save/locking semantics for it (GroupStateStore
 * and its callers) uses this instead.
 * inMemory(Map) takes an explicit backing map so it can share storage with an
 * InMemoryObjectStorageClient in the same test - in production both ObjectStorageClient and
 * ControlPlaneStateStore hit the same MinIO bucket under the same object keys; GroupStateStore
 * relies on that (it reads the group record via ObjectStorageClient but writes it atomically
 * via ControlPlaneStateStore), so the two fakes need to agree on one map or a write via one is
 * invisible to a read via the other.
 */
public final class ControlPlaneStateStores {

    private static final String BUCKET = "bridge-raw";

    private ControlPlaneStateStores() {
    }

    public static ControlPlaneStateStore inMemory() {
        return inMemory(new ConcurrentHashMap<>());
    }

    public static ControlPlaneStateStore inMemory(Map<String, byte[]> fakeObjects) {
        MinioClient minioClient = mock(MinioClient.class);
        try {
            when(minioClient.getObject(any(GetObjectArgs.class))).thenAnswer(invocation -> {
                GetObjectArgs args = invocation.getArgument(0);
                byte[] content = fakeObjects.get(args.object());
                if (content == null) {
                    ErrorResponse notFound = new ErrorResponse(
                            "NoSuchKey", "not found", BUCKET, args.object(), null, null, null);
                    throw new ErrorResponseException(notFound, null, "req-1");
                }
                return new GetObjectResponse(new Headers.Builder().build(), BUCKET, "us-east-1",
                        args.object(), new ByteArrayInputStream(content));
            });
            when(minioClient.putObject(any(PutObjectArgs.class))).thenAnswer(invocation -> {
                PutObjectArgs args = invocation.getArgument(0);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                args.stream().transferTo(buffer);
                fakeObjects.put(args.object(), buffer.toByteArray());
                return null;
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new ControlPlaneStateStore(minioClient, objectMapper, new KeyedLock(), BUCKET);
    }
}
