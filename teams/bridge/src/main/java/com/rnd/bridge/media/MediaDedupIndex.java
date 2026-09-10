package com.rnd.bridge.media;

import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.storage.ControlPlaneStateStore;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Content-hash dedup index for the media lane, stored in MinIO rather than a new datastore -
 * same reasoning plan.md (Malki's lane) uses for cursor persistence
 * (`_cursors/{sourceType}/{tenantId}.json`): it's small control-plane state, not lane content,
 * so it doesn't go through the shared ObjectStorageClient (which is for landing raw content,
 * tagged and content-addressed by object key) - it goes through ControlPlaneStateStore, at a
 * path prefix (`_dedup/`) that stays out of the object_key pattern
 * ([A-Z_]+/tenant/yyyy-MM-dd/file) every real landed object follows.
 * <p>
 * {@link #checkAndRecord} is the only way to touch this index - it checks and (if new) records
 * a hash in one call, under one lock, via {@link ControlPlaneStateStore}. This used to be two
 * separate load()/save() methods with the check-and-add done by the caller in between, which
 * left a load-then-save race window open for two concurrent runs on the same
 * (sourceType, tenantId); ControlPlaneStateStore closes that window.
 */
@Component
public class MediaDedupIndex {

    private static final String PREFIX = "_dedup";
    private static final String[] EMPTY = new String[0];

    private final ControlPlaneStateStore stateStore;

    public MediaDedupIndex(ControlPlaneStateStore stateStore) {
        this.stateStore = stateStore;
    }

    /**
     * Atomically checks whether contentHash was already recorded for (sourceType, tenantId)
     * and, if not, records it now - in one locked load-mutate-save cycle, so two concurrent
     * calls for the same (sourceType, tenantId) can't drop each other's recorded hashes.
     *
     * @return true if contentHash was already present (this is a duplicate, not newly
     *         recorded); false if it was just recorded for the first time.
     */
    public boolean checkAndRecord(RawSourceType sourceType, String tenantId, String contentHash) {
        AtomicBoolean alreadyPresent = new AtomicBoolean(false);
        stateStore.update(indexKey(sourceType, tenantId), String[].class, EMPTY, current -> {
            for (String existing : current) {
                if (existing.equals(contentHash)) {
                    alreadyPresent.set(true);
                    return current;
                }
            }
            String[] updated = Arrays.copyOf(current, current.length + 1);
            updated[current.length] = contentHash;
            return updated;
        });
        return alreadyPresent.get();
    }

    /**
     * Rolls back a hash recorded by {@link #checkAndRecord} whose upload turned out to fail
     * after the hash was already claimed - without this, a failed upload would be marked as
     * "already seen" forever and its content would never get a real chance to land on retry.
     */
    public void forget(RawSourceType sourceType, String tenantId, String contentHash) {
        stateStore.update(indexKey(sourceType, tenantId), String[].class, EMPTY, current -> {
            int index = indexOf(current, contentHash);
            if (index < 0) {
                return current;
            }
            String[] updated = new String[current.length - 1];
            System.arraycopy(current, 0, updated, 0, index);
            System.arraycopy(current, index + 1, updated, index, current.length - index - 1);
            return updated;
        });
    }

    private int indexOf(String[] values, String target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(target)) {
                return i;
            }
        }
        return -1;
    }

    private String indexKey(RawSourceType sourceType, String tenantId) {
        return "%s/%s/%s.json".formatted(PREFIX, sourceType.name(), tenantId);
    }
}
