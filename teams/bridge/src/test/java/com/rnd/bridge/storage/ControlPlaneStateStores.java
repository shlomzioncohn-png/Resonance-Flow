package com.rnd.bridge.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.core.KeyedLock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A ControlPlaneStateStore backed by an in-memory {@link InMemoryObjectStorageClient} instead of
 * a running object-storage instance.
 * inMemory(Map) takes an explicit backing map so it can share storage with an
 * InMemoryObjectStorageClient in the same test - in production both ObjectStorageClient and
 * ControlPlaneStateStore hit the same bucket under the same object keys; GroupStateStore relies
 * on that (it reads the group record via ObjectStorageClient but writes it atomically via
 * ControlPlaneStateStore), so the two fakes need to agree on one map or a write via one is
 * invisible to a read via the other.
 */
public final class ControlPlaneStateStores {

    private ControlPlaneStateStores() {
    }

    public static ControlPlaneStateStore inMemory() {
        return inMemory(new ConcurrentHashMap<>());
    }

    public static ControlPlaneStateStore inMemory(Map<String, byte[]> fakeObjects) {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new ControlPlaneStateStore(new InMemoryObjectStorageClient(fakeObjects), objectMapper, new KeyedLock());
    }
}
