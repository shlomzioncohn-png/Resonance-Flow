package com.rnd.bridge.storage;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trivial in-memory ObjectStorageClient test double, for tests that need real
 * save-then-load round trips (e.g. across simulated intake runs) without a live MinIO.
 * The backing map can be shared with a {@link ControlPlaneStateStores#inMemory} instance (see
 * that class's javadoc) - in production both ObjectStorageClient and ControlPlaneStateStore
 * hit the same MinIO bucket, so a test double for code that reads via one and writes via the
 * other (GroupStateStore) needs the same sharing or a write on one side is invisible to a read
 * on the other.
 */
public class InMemoryObjectStorageClient implements ObjectStorageClient {

    private final Map<String, byte[]> objects;

    public InMemoryObjectStorageClient() {
        this(new ConcurrentHashMap<>());
    }

    public InMemoryObjectStorageClient(Map<String, byte[]> objects) {
        this.objects = objects;
    }

    @Override
    public PutResult putObject(String objectKey, byte[] content, String contentType, Map<String, String> tags) {
        objects.put(objectKey, content);
        return new PutResult(contentType, content.length);
    }

    @Override
    public Optional<byte[]> getObject(String objectKey) {
        return Optional.ofNullable(objects.get(objectKey));
    }
}
