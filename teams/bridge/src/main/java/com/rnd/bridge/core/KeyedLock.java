package com.rnd.bridge.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * Per-key mutual exclusion, shared by any lane that does a load-then-save cycle against small
 * control-plane state in MinIO (a dedup index, a sync cursor) that isn't safe for two concurrent
 * callers touching the same key - see storage.ControlPlaneStateStore, media.MediaDedupIndex, and
 * plan.md's "Cursor persistence" section (which flags the identical race for the future
 * {@code _cursors/...} state).
 * <p>
 * Only serializes callers within this one JVM instance - it does not help once more than one
 * app instance can run the same lane concurrently. That case needs a MinIO-native conditional
 * write instead; out of scope while every lane runs single-instance (same call plan.md already
 * made for cursor persistence).
 */
@Component
public final class KeyedLock {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** Runs criticalSection with exclusive access for the given key, blocking other callers using the same key. */
    public void run(String key, Runnable criticalSection) {
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            criticalSection.run();
        } finally {
            lock.unlock();
        }
    }
}
