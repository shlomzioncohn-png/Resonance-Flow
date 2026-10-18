package com.rnd.bridge.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.core.KeyedLock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Atomic read-modify-write for small control-plane JSON blobs kept in object storage — a dedup index, a
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

    private final ObjectStorageClient storage;
    private final ObjectMapper objectMapper;
    private final KeyedLock lock;

    public ControlPlaneStateStore(
            @Qualifier("postgresObjectStorageClient") ObjectStorageClient storage, ObjectMapper objectMapper,
            KeyedLock lock) {
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.lock = lock;
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
        try {
            Optional<byte[]> content = storage.getObject(key);
            if (content.isEmpty()) {
                return emptyValue;
            }
            return objectMapper.readValue(content.get(), type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read control-plane state " + key, e);
        }
    }

    private void save(String key, Object value) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(value);
            storage.putObject(key, json, "application/json", Map.of());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write control-plane state " + key, e);
        }
    }
}
